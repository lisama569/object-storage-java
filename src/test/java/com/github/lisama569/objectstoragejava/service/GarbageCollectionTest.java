package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.tests.StorageTestSupport;

import com.github.lisama569.objectstoragejava.dto.GarbageCollectionReport;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.VersionInfo;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import com.github.lisama569.objectstoragejava.exception.VersionConflictException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reclamation rules under test:
 * <ul>
 *     <li>only blobs referenced by NO readable version are candidates;</li>
 *     <li>a blob shared with any live version/object is never deleted;</li>
 *     <li>retirement marks a version unreadable and makes its exclusive blobs reclaimable;</li>
 *     <li>young blobs and blobs held by a read lease are skipped;</li>
 *     <li>collect() is retry-safe (idempotent: second pass deletes nothing extra).</li>
 * </ul>
 */
class GarbageCollectionTest {

    @TempDir
    Path tempDir;

    private long countBlobs() throws Exception {
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            return blobs.count();
        }
    }

    private Set<String> blobNames() throws Exception {
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            return blobs.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    /** Age every blob by {@code minutes} so the grace window does not interfere with assertions. */
    private void ageBlobs(long minutes) throws Exception {
        Instant old = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            for (Path blob : blobs.toList()) {
                Files.setLastModifiedTime(blob, FileTime.from(old));
            }
        }
    }

    @Test
    void replacingVersionRetiresOldThenReclaimsExclusiveBlobsButKeepsShared() throws Exception {
        var stack = StorageTestSupport.newStack(tempDir);
        var gc = new GarbageCollectorService(
                new com.github.lisama569.objectstoragejava.service.StorageLayout(
                        new com.github.lisama569.objectstoragejava.config.StorageProperties(
                                tempDir.toString())),
                stack.leaseManager(), "1m");

        // v1 = two parts [A, B]; v2 = two parts [A, C] -> blob A is shared, blob B becomes exclusive.
        byte[] partA = "shared-block-A".getBytes();
        byte[] partB = "only-in-version-one".getBytes();
        byte[] partC = "only-in-version-two-new".getBytes();
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "evo", List.of(partA, partB));
        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "evo", List.of(partA, partC));
        String blobB = v1.partChecksums().get(1) + ".dat";
        String blobA = v2.partChecksums().get(0) + ".dat";
        assertEquals(3, countBlobs(), "A, B, C all materialised");

        // v1 is still LIVE, so GC reclaims nothing even after aging.
        ageBlobs(10);
        GarbageCollectionReport firstPass = gc.collect();
        assertEquals(0, firstPass.deletedBlobs(), "live historical version still references B");
        assertEquals(3, countBlobs());

        // Retire v1: it becomes unreadable and B is no longer referenced by any readable version.
        VersionInfo retired = stack.service().retireVersion("evo", 1);
        assertEquals("RETIRED", retired.state());
        assertThrows(UploadNotFoundException.class,
                () -> stack.service().readVersion("evo", 1), "retired version is no longer readable");
        assertEquals(2, stack.service().headObject("evo").version(), "current v2 unaffected");

        // The retired marker is brand new; age the exclusive blob B to pass the grace window.
        ageBlobs(10);
        GarbageCollectionReport secondPass = gc.collect();
        assertEquals(1, secondPass.deletedBlobs(), "only B is unreferenced");
        assertEquals(Set.of(blobA, v2.partChecksums().get(1) + ".dat"), blobNames(),
                "shared A and current C survive; exclusive B is reclaimed");

        // Retry-safe: a second pass deletes nothing.
        GarbageCollectionReport thirdPass = gc.collect();
        assertEquals(0, thirdPass.deletedBlobs(), "retry deletes nothing extra");
        System.out.printf("[gc] retire v1 -> reclaimed %s; kept shared A + current C; states=%s%n",
                secondPass.deletedChecksums(),
                stack.service().listVersions("evo").stream().map(VersionInfo::state).toList());
    }

    @Test
    void currentVersionCannotBeRetired() {
        var stack = StorageTestSupport.newStack(tempDir);
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "x",
                List.of("hello".getBytes()));
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> stack.service().retireVersion("x", 1));
        System.out.println("[retire] rejecting current version -> " + ex.getMessage());
    }

    @Test
    void youngBlobsAndLeasedBlobsAreProtected() throws Exception {
        var stack = StorageTestSupport.newStack(tempDir);
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "p", List.of("one".getBytes()));
        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "p", List.of("two".getBytes()));
        stack.service().retireVersion("p", 1);
        String oldBlob = v1.partChecksums().get(0);
        String newBlob = v2.partChecksums().get(0);
        var gc = new GarbageCollectorService(
                new com.github.lisama569.objectstoragejava.service.StorageLayout(
                        new com.github.lisama569.objectstoragejava.config.StorageProperties(
                                tempDir.toString())),
                stack.leaseManager(), "1h");

        // Grace window: old blob is young -> skipped (new blob is referenced anyway).
        GarbageCollectionReport young = gc.collect();
        assertEquals(1, young.skippedTooYoung(), "young unreferenced blob must be skipped");
        assertEquals(2, countBlobs());

        // Age only the old blob, but hold a lease on it -> protected as in-use.
        Files.setLastModifiedTime(tempDir.resolve("blobs").resolve(oldBlob + ".dat"),
                FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        try (BlobLeaseManager.Lease ignored = stack.leaseManager().acquire(List.of(oldBlob))) {
            GarbageCollectionReport leased = gc.collect(Instant.now());
            assertEquals(1, leased.skippedInUse(), "leased blob must not be deleted");
            assertEquals(2, countBlobs());
        }
        // Lease released + old enough -> reclaimed.
        GarbageCollectionReport freed = gc.collect(Instant.now());
        assertEquals(1, freed.deletedBlobs());
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(oldBlob + ".dat")));
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(newBlob + ".dat")));
        System.out.println("[gc] protection order: referenced > in-use lease > grace age -> delete");
    }

    @Test
    void gcRunsWhileReadsAndWritesProceedWithoutCorruption() throws Exception {
        var stack = StorageTestSupport.newStack(tempDir);
        var gc = new GarbageCollectorService(
                new com.github.lisama569.objectstoragejava.service.StorageLayout(
                        new com.github.lisama569.objectstoragejava.config.StorageProperties(
                                tempDir.toString())),
                stack.leaseManager(), "0s");
        // v1 content remains readable throughout; publish v2 and retire v1 while repeatedly GC-ing.
        List<byte[]> v1 = List.of("persistent-v1-content".getBytes());
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "busy", v1);
        List<byte[]> v2 = List.of("busy-v2-content!!".getBytes());
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "busy", v2);
        // Keep v1 live (not retired) during reads, GC must keep its blobs.
        for (int i = 0; i < 5; i++) {
            gc.collect();
            byte[] got = stack.service().readVersion("busy", 1).readAllBytes();
            assertTrue(java.util.Arrays.equals(StorageTestSupport.concat(v1), got),
                    "live v1 must remain byte-exact while GC runs concurrently with traffic");
        }
        Map<Long, String> states = stack.service().listVersions("busy").stream()
                .collect(Collectors.toMap(VersionInfo::version, VersionInfo::state));
        assertEquals(Map.of(1L, "LIVE", 2L, "CURRENT"), states);
        System.out.println("[gc-concurrency] repeated GC during reads kept every live blob readable");
    }
}
