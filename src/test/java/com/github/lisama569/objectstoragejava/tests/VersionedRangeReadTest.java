package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.OpenedObject;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.InvalidManifestException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flexible reads:
 * <ul>
 *     <li>read a specific historical version while newer versions exist;</li>
 *     <li>read a byte range within one version, including ranges spanning several chunks;</li>
 *     <li>metadata returned with a read always identifies the exact version whose bytes stream;</li>
 *     <li>ranged reads still verify every touched blob's SHA-256.</li>
 * </ul>
 */
class VersionedRangeReadTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    /** v1 = 3 chunks of 10 bytes each: bytes 00..29. */
    private final List<byte[]> v1 = List.of(
            "0123456789".getBytes(),
            "abcdefghij".getBytes(),
            "ABCDEFGHIJ".getBytes());

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
    }

    @Test
    void readsHistoricalVersionByteExactAfterNewerVersionPublished() throws Exception {
        StoredObject v1Meta = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "doc", v1);
        List<byte[]> v2Parts = List.of("totally-different-second-version".getBytes());
        StoredObject v2Meta = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "doc", v2Parts);

        OpenedObject opened = stack.service().openVersionSnapshot("doc", 1, null);
        assertEquals(1, opened.metadata().version(), "snapshot metadata must identify v1");
        assertArrayEquals(StorageTestSupport.concat(v1), opened.stream().readAllBytes(),
                "historical v1 must be byte-exact even while v2 is current");
        System.out.printf("[read-history] current=v%d but explicit snapshot=v%d contentChecksum=%s%n",
                v2Meta.version(), opened.metadata().version(), opened.metadata().contentChecksum());
    }

    @Test
    void rangesAreInclusiveNormalisedAndClipped() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doc", v1);
        byte[] all = StorageTestSupport.concat(v1);

        // Explicit interval inside the first chunk.
        try (OpenedObject o = stack.service().openVersionSnapshot("doc", 1, "bytes=2-5")) {
            assertEquals(2, o.range().start());
            assertEquals(5, o.range().end());
            assertArrayEquals(slice(all, 2, 6), o.stream().readAllBytes());
        }
        // Open-ended: bytes=20- -> to the end (crosses into the last chunk).
        try (OpenedObject o = stack.service().openVersionSnapshot("doc", 1, "bytes=20-")) {
            assertEquals(20, o.range().start());
            assertEquals(29, o.range().end());
            assertArrayEquals(slice(all, 20, 30), o.stream().readAllBytes());
        }
        // Suffix: bytes=-7 -> last 7 bytes.
        try (OpenedObject o = stack.service().openVersionSnapshot("doc", 1, "bytes=-7")) {
            assertEquals(23, o.range().start());
            assertEquals(29, o.range().end());
            assertArrayEquals(slice(all, 23, 30), o.stream().readAllBytes());
        }
        // Over-long end is clipped to object size.
        try (OpenedObject o = stack.service().openVersionSnapshot("doc", 1, "bytes=25-999")) {
            assertEquals(29, o.range().end());
            assertArrayEquals(slice(all, 25, 30), o.stream().readAllBytes());
        }
        System.out.println("[read-range] inclusive/open/suffix/clipped ranges all return exact windows");
    }

    @Test
    void rangeAcrossChunkBoundariesIsByteExact() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doc", v1);
        byte[] all = StorageTestSupport.concat(v1);
        // Spans all three chunks: start in chunk1, end in chunk3.
        try (OpenedObject o = stack.service().openCurrent("doc", "bytes=7-22")) {
            assertEquals(1, o.metadata().version(), "range metadata must come from one snapshot");
            byte[] got = o.stream().readAllBytes();
            assertArrayEquals(slice(all, 7, 23), got, "cross-chunk window must be exact");
            System.out.printf("[read-range] cross-chunk bytes=7-22 (3 blobs) length=%d verified per-blob%n",
                    got.length);
        }
        // Boundary-exact windows around chunk edges.
        try (OpenedObject o = stack.service().openCurrent("doc", "bytes=9-10")) {
            assertArrayEquals(slice(all, 9, 11), o.stream().readAllBytes());
        }
        try (OpenedObject o = stack.service().openCurrent("doc", "bytes=0-29")) {
            assertArrayEquals(all, o.stream().readAllBytes());
        }
    }

    @Test
    void invalidAndUnsatisfiableRangesAreDistinguished() {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doc", v1);
        assertThrows(InvalidManifestException.class,
                () -> stack.service().openVersionSnapshot("doc", 1, "items=1-2"),
                "non-byte ranges are rejected as invalid");
        assertThrows(InvalidManifestException.class,
                () -> stack.service().openVersionSnapshot("doc", 1, "bytes=1-2,3-4"),
                "multiple ranges are rejected as invalid");
        RangeNotSatisfiableException ex = assertThrows(RangeNotSatisfiableException.class,
                () -> stack.service().openVersionSnapshot("doc", 1, "bytes=100-"));
        assertEquals(30, ex.objectSize());
        System.out.println("[read-range] invalid range -> 400; unsatisfiable range -> 416 size="
                + ex.objectSize());
    }

    @Test
    void rangedReadDetectsACorruptedTouchedBlob() throws Exception {
        StoredObject meta = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "doc", v1);
        // Corrupt the second chunk's blob; a range touching it must fail integrity verification,
        // even though the window only partially overlaps that blob.
        Path blob2 = tempDir.resolve("blobs").resolve(meta.partChecksums().get(1) + ".dat");
        byte[] corrupted = "xxxxxxxxxx".getBytes();
        java.nio.file.Files.write(blob2, corrupted);

        try (OpenedObject o = stack.service().openCurrent("doc", "bytes=7-22")) {
            ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                    () -> o.stream().readAllBytes());
            System.out.println("[read-integrity] corrupted middle blob detected during ranged read: "
                    + ex.getMessage());
            assertTrue(ex.getMessage().contains("Integrity check failed"));
        }
    }

    private static byte[] slice(byte[] data, int from, int toExclusive) {
        byte[] out = new byte[toExclusive - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }
}
