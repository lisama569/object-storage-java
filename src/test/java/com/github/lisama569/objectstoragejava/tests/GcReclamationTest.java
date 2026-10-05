package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.GcReport;
import com.github.lisama569.objectstoragejava.dto.PruneResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import com.github.lisama569.objectstoragejava.service.StorageLayout;
import com.github.lisama569.objectstoragejava.service.StorageReclamationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Version retirement and blob reclamation coverage:
 * <ul>
 *   <li>prune states exactly which versions stay readable / disappear, current always survives;</li>
 *   <li>GC reclaims only blobs with no readable reference and reports its counters;</li>
 *   <li>a blob referenced by ANY surviving version (incl. a different object) is never deleted;</li>
 *   <li>an in-flight reader blocks reclamation of its blobs until the stream closes;</li>
 *   <li>repeated GC passes delete nothing twice (idempotent/retriable).</li>
 * </ul>
 */
class GcReclamationTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;
    private StorageLayout layout;
    private StorageReclamationService gc;

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
        layout = new StorageLayout(new com.github.lisama569.objectstoragejava.config.StorageProperties(
                tempDir.toString()));
        // MUST share the service's gate: the whole safety argument is one gate coordinating
        // readers and the exclusive GC pass.
        gc = new StorageReclamationService(layout, stack.readGate(), stack.service());
    }

    private List<StoredObject> publishVersions(String name, int count) {
        List<StoredObject> versions = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            versions.add(StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), name,
                    List.of(StorageTestSupport.utf8(name + "-version-" + i + "-payload"))));
        }
        return versions;
    }

    private long blobs() throws Exception {
        try (var s = Files.list(tempDir.resolve("blobs"))) {
            return s.count();
        }
    }

    @Test
    void pruneStatesReadableVersusRemovedAndCurrentAlwaysSurvives() {
        List<StoredObject> all = publishVersions("evolving.log", 4);
        PruneResult result = stack.service().pruneVersions("evolving.log", 2);

        assertEquals(4, result.currentVersion());
        assertEquals(List.of(3L, 4L), result.retainedVersions());
        assertEquals(List.of(1L, 2L), result.removedVersions());
        System.out.printf("[gc] prune retain=2: retained=%s removed=%s current=v%d%n",
                result.retainedVersions(), result.removedVersions(), result.currentVersion());

        // retained stay byte-exact
        for (long v : result.retainedVersions()) {
            StoredObject meta = stack.service().getVersion("evolving.log", v);
            byte[] bytes;
            try (InputStream in = stack.service().readVersion("evolving.log", v)) {
                bytes = in.readAllBytes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertArrayEquals(StorageTestSupport.utf8("evolving.log-version-" + v + "-payload"), bytes);
        }
        // removed versions are gone (404), metadata file absent
        for (long v : result.removedVersions()) {
            assertThrows(UploadNotFoundException.class,
                    () -> stack.service().readVersionRange("evolving.log", v, null));
            assertFalse(Files.exists(layout.versionFile("evolving.log", v)));
        }
        // retain < 1 rejected
        assertThrows(RuntimeException.class, () -> stack.service().pruneVersions("evolving.log", 0));
    }

    @Test
    void gcReclaimsOnlyUnreferencedBlobsAndIsIdempotentOnRetry() throws Exception {
        List<StoredObject> all = publishVersions("a.txt", 3);
        long before = blobs();
        assertEquals(3, before, "three distinct payloads -> three blobs");

        // Retire v1 and v2; v3 survives, so the v1/v2 blobs become unreferenced.
        stack.service().pruneVersions("a.txt", 1);
        GcReport first = gc.collectGarbage(0);
        // With minAgeMillis=0 the same blobs are quarantined in phase A AND deleted by phase B of
        // the same pass, so both stage counters are 2.
        assertEquals(2, first.quarantined(), "phase A moves the two unreferenced blobs");
        assertEquals(2, first.deleted(), "phase B deletes the aged-out quarantined blobs");
        assertEquals(0, first.restored());
        long afterFirst = blobs();
        assertEquals(1, afterFirst, "only the current version's blob remains");
        System.out.printf("[gc] pass1 examined=%d quarantined=%d deleted=%d restored=%d blobs=%d->%d%n",
                first.blobsExamined(), first.quarantined(), first.deleted(), first.restored(), before, afterFirst);

        // Surviving version still byte-exact.
        try (InputStream in = stack.service().readObject("a.txt")) {
            assertArrayEquals(StorageTestSupport.utf8("a.txt-version-3-payload"), in.readAllBytes());
        }

        // Second pass: nothing left to delete, counters stay zero — retry deletes nothing twice.
        GcReport second = gc.collectGarbage(0);
        assertEquals(0, second.quarantined(), "retry must not re-quarantine anything");
        assertEquals(0, second.deleted(), "retry must not delete anything new");
        assertEquals(afterFirst, blobs());
        assertTrue(Files.list(layout.quarantineDir()).count() == 0 || second.deleted() == 0);
        System.out.printf("[gc] pass2 (retry) examined=%d quarantined=%d deleted=%d restored=%d (idempotent)%n",
                second.blobsExamined(), second.quarantined(), second.deleted(), second.restored());
    }

    @Test
    void blobSharedWithAnotherSurvivingVersionIsNeverDeleted() throws Exception {
        // object a: two versions, then prune v1. object b: one version reusing... ensure a shared
        // blob by uploading the SAME bytes to a different object (content addressing shares it).
        byte[] shared = StorageTestSupport.utf8("shared-by-both-objects-payload");
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "a", List.of(shared));
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "a",
                List.of(StorageTestSupport.utf8("a-only-second-version")));
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "b", List.of(shared));

        stack.service().pruneVersions("a", 1); // a/v1 metadata removed, but b still references blob
        GcReport report = gc.collectGarbage(0);
        assertEquals(0, report.deleted(), "blob referenced by object b must survive");
        try (InputStream in = stack.service().readObject("b")) {
            assertArrayEquals(shared, in.readAllBytes());
        }
        System.out.printf("[gc] cross-object live reference honored: deleted=%d, b still byte-exact%n",
                report.deleted());
    }

    @Test
    void inFlightReaderKeepsItsBlobsAliveUntilClosed() throws Exception {
        List<StoredObject> all = publishVersions("streaming.bin", 2);
        // Hold a v1 read stream open, retire v1, then run GC concurrently.
        var pinned = stack.service().readVersionRange("streaming.bin", 1, null);
        stack.service().pruneVersions("streaming.bin", 1);

        Thread gcThread = new Thread(() -> gc.collectGarbage(0));
        gcThread.start();
        // GC must be blocked by the open reader (give it a moment to reach the gate).
        Thread.sleep(200);
        long blobsWhileReading = blobs();
        assertEquals(2, blobsWhileReading, "GC must not move a blob that a reader is streaming");
        System.out.printf("[gc] reader open -> blobs=%d retained; GC waits on the read gate%n", blobsWhileReading);

        byte[] bytes;
        try (InputStream in = pinned.content()) {
            bytes = in.readAllBytes();
        }
        assertArrayEquals(StorageTestSupport.utf8("streaming.bin-version-1-payload"), bytes);
        gcThread.join(30_000);
        assertFalse(gcThread.isAlive(), "GC must complete once the reader closes");
        assertEquals(1, blobs(), "after the reader closes, the old blob is reclaimed");
        System.out.println("[gc] after reader closed: old blob reclaimed, current version intact");
    }

    @Test
    void deletedObjectBlobsAreReclaimedButSurvivorsAreNot() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doomed",
                List.of(StorageTestSupport.utf8("doomed-object-only-bytes")));
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "keeper",
                List.of(StorageTestSupport.utf8("keeper-object-bytes")));
        long before = blobs();
        assertEquals(2, before);

        stack.service().deleteObject("doomed");
        assertThrows(UploadNotFoundException.class, () -> stack.service().headObject("doomed"));
        GcReport report = gc.collectGarbage(0);
        assertEquals(1, blobs(), "doomed blob gone, keeper blob remains");
        try (InputStream in = stack.service().readObject("keeper")) {
            assertArrayEquals(StorageTestSupport.utf8("keeper-object-bytes"), in.readAllBytes());
        }
        System.out.printf("[gc] deleteObject -> doomed blob reclaimed (deleted=%d), keeper byte-exact%n",
                report.deleted());
    }
}
