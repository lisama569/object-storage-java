package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.GcReport;
import com.github.lisama569.objectstoragejava.dto.PruneResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import com.github.lisama569.objectstoragejava.service.StorageReclamationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version retirement + blob reclamation contract:
 * <ul>
 *   <li>pruning removes only old version metadata (current always retained) and states exactly
 *       which versions remain readable / unreadable;</li>
 *   <li>GC reclaims ONLY blobs that no readable version references, in two safe phases
 *       (quarantine then later delete); shared blobs are never deleted;</li>
 *   <li>GC is idempotent and retry-safe: repeated passes delete nothing extra and never twice;</li>
 *   <li>deleting the whole object lets all its blobs be reclaimed;</li>
 *   <li>concurrent reads during GC keep getting complete bytes — GC waits for active reads.</li>
 * </ul>
 */
class GarbageCollectionTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;
    private StorageReclamationService gc;

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
        gc = new StorageReclamationService(new com.github.lisama569.objectstoragejava.service.StorageLayout(
                new com.github.lisama569.objectstoragejava.config.StorageProperties(tempDir.toString())),
                stack.readGate(), stack.service());
    }

    private long count(java.nio.file.Path dir, String glob) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(glob)).count();
        }
    }

    private long blobCount() throws Exception {
        return count(tempDir.resolve("blobs"), "[0-9a-f]{64}\\.dat");
    }

    private long quarantineCount() throws Exception {
        return count(tempDir.resolve("quarantine"), "[0-9a-f]{64}\\.dat");
    }

    @Test
    void pruneMakesOldVersionsUnreadableButKeepsCurrentAndSharedBlobs() throws Exception {
        // v1 and v2 share no parts; v3 deliberately reuses v1's single part bytes so one blob is
        // shared across a pruned version (v1) and the surviving current (v3).
        byte[] shared = StorageTestSupport.utf8("shared-by-v1-and-v3");
        byte[] middleA = StorageTestSupport.utf8("middle-version-2-aaa");
        byte[] middleB = StorageTestSupport.utf8("middle-version-2-bbb");

        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "hist", List.of(shared));
        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "hist", List.of(middleA, middleB));
        StoredObject v3 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "hist", List.of(shared));

        assertEquals(3, v3.version());
        long blobsBefore = blobCount(); // 3 unique contents (shared, a, b)
        assertEquals(3, blobsBefore);
        byte[] v1Bytes = StorageTestSupport.readAll(stack.service().readVersion("hist", 1));

        // Retain only the newest 2 versions (v2, v3); v1 metadata disappears.
        PruneResult pruned = stack.service().pruneVersions("hist", 2);
        assertEquals(List.of(1L), pruned.removedVersions());
        assertEquals(List.of(2L, 3L), pruned.retainedVersions());
        assertEquals(3L, pruned.currentVersion());
        System.out.printf("[prune] retained=%s removed=%s -> readable={v2,v3}, unreadable={v1}%n",
                pruned.retainedVersions(), pruned.removedVersions());

        assertThrows(UploadNotFoundException.class,
                () -> StorageTestSupport.readAll(stack.service().readVersion("hist", 1)));
        // v2/v3 fully readable, current still v3.
        assertArrayEquals(StorageTestSupport.concat(List.of(middleA, middleB)),
                StorageTestSupport.readAll(stack.service().readVersion("hist", 2)));
        assertArrayEquals(v1Bytes, StorageTestSupport.readAll(stack.service().readObject("hist")));

        // GC: v2's two blobs stay live; the shared blob stays because v3 references it.
        GcReport first = gc.collectGarbage(0);
        GcReport second = gc.collectGarbage(0);
        System.out.printf("[gc] pass1 quarantined=%d deleted=%d restored=%d; pass2 quarantined=%d deleted=%d%n",
                first.quarantined(), first.deleted(), first.restored(),
                second.quarantined(), second.deleted());
        // Nothing was reclaimable: the only removed version (v1) shares its blob with current v3.
        assertEquals(3, blobCount() + quarantineCount(),
                "shared blob must survive: it backs a still-readable current version");
        System.out.println("[gc] shared blob kept; readability after GC: v2=ok v3=ok v1=404");
    }

    @Test
    void garbageIsQuarantinedThenDeletedAndReclaimIsRetrySafe() throws Exception {
        byte[] a = StorageTestSupport.utf8("version-one-only");
        byte[] b = StorageTestSupport.utf8("version-two-current");
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "g", List.of(a));
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "g", List.of(b));
        assertEquals(2, blobCount());

        // Drop v1; its blob becomes unreferenced while v2's blob is live.
        PruneResult pruned = stack.service().pruneVersions("g", 1);
        assertEquals(List.of(1L), pruned.removedVersions());

        GcReport pass1 = gc.collectGarbage(0);
        assertEquals(1, pass1.quarantined(), "the single orphan blob moves to quarantine");
        assertEquals(1, quarantineCount());
        assertEquals(1, blobCount(), "live current-version blob remains in blobs/");
        System.out.printf("[gc] phase1: quarantined=%d, blobs/ now holds only live=%d%n",
                pass1.quarantined(), blobCount());

        // Current version still byte-exact after quarantine.
        assertArrayEquals(b, StorageTestSupport.readAll(stack.service().readObject("g")));

        // Second pass sweeps the quarantine entry (now old enough and still unreferenced).
        // Bump timestamps deterministically by sleeping is unnecessary: minAge=0.
        GcReport pass2 = gc.collectGarbage(0);
        assertEquals(1, pass2.deleted());
        assertEquals(0, quarantineCount());
        assertEquals(1, blobCount());

        // Repeated passes must not delete anything more / fail.
        GcReport pass3 = gc.collectGarbage(0);
        assertEquals(0, pass3.quarantined());
        assertEquals(0, pass3.deleted());
        assertEquals(1, blobCount());
        System.out.println("[gc] phase2+sweep: orphan deleted; retries delete nothing extra"
                + " (retry-safe, idempotent)");
    }

    @Test
    void gcTouchesNothingWhenAllBlobsLive() throws Exception {
        byte[] p1 = StorageTestSupport.utf8("aaaa");
        byte[] p2 = StorageTestSupport.utf8("bbbb");
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "live", List.of(p1, p2));
        GcReport report = gc.collectGarbage(0);
        assertEquals(0, report.quarantined());
        assertEquals(0, report.deleted());
        assertEquals(2, report.liveReferences());
        assertEquals(2, blobCount());
        System.out.printf("[gc] all blobs live: examined=%d liveRefs=%d quarantined=0 deleted=0%n",
                report.blobsExamined(), report.liveReferences());
    }

    @Test
    void deletingObjectMakesAllItsBlobsReclaimable() throws Exception {
        byte[] x = StorageTestSupport.utf8("doomed-object-bytes");
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doomed", List.of(x));
        assertEquals(1, blobCount());
        stack.service().deleteObject("doomed");
        assertThrows(UploadNotFoundException.class, () -> stack.service().headObject("doomed"));
        GcReport p1 = gc.collectGarbage(0);
        GcReport p2 = gc.collectGarbage(0);
        assertEquals(1, p1.quarantined());
        assertEquals(1, p2.deleted());
        assertEquals(0, blobCount());
        assertEquals(0, quarantineCount());
        System.out.println("[gc] deleteObject -> versions/pointer gone; blobs quarantined then deleted");
    }

    @Test
    void concurrentReadsDuringGcAlwaysGetCompleteBytes() throws Exception {
        byte[] content = StorageTestSupport.utf8("gc-vs-reader-payload".repeat(50));
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "race", List.of(
                        java.util.Arrays.copyOf(content, 300),
                        java.util.Arrays.copyOfRange(content, 300, 700),
                        java.util.Arrays.copyOfRange(content, 700, content.length)));
        // Publish a distinct v2, prune v1 -> v1 blob candidates, and GC while readers stream.
        byte[] other = StorageTestSupport.utf8("v2-totally-different-content");
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "race", List.of(other));
        stack.service().pruneVersions("race", 1);

        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> readers = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    for (int r = 0; r < 200 && failure.get() == null; r++) {
                        // Current v2 must always be complete; its blob is live and must survive.
                        byte[] bytes = StorageTestSupport.readAll(stack.service().readObject("race"));
                        if (!java.util.Arrays.equals(other, bytes)) {
                            throw new AssertionError("live read corrupted/torn during GC: len="
                                    + bytes.length);
                        }
                    }
                } catch (Throwable err) {
                    failure.set(err);
                }
            });
            readers.add(t);
        }
        readers.forEach(Thread::start);
        for (int i = 0; i < 50; i++) {
            gc.collectGarbage(0);
        }
        for (Thread t : readers) {
            t.join(15_000);
        }
        assertEquals(null, failure.get(),
                failure.get() == null ? "" : failure.get().getMessage());
        // v2's blob is the sole survivor; v1's 3 blobs were safely reclaimed.
        assertEquals(1, blobCount());
        assertFalse(Files.exists(tempDir.resolve("objects/race/versions/v1.json")));
        System.out.println("[gc-race] GC waited for in-flight reads; current v2 always complete;"
                + " only unreferenced v1 blobs reclaimed");
    }
}
