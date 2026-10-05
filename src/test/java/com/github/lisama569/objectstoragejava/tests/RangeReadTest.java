package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.RangeReadResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read pipeline coverage:
 * <ul>
 *   <li>full read of current and historical versions returns complete exact bytes;</li>
 *   <li>range reads: start-end, start-only, suffix; including ranges spanning chunk boundaries;</li>
 *   <li>every part touched by a range is fully checksum-verified (tampered blob -> error, never
 *       partial or wrong bytes);</li>
 *   <li>version metadata and bytes come from one snapshot even while versions are published;</li>
 *   <li>invalid/unsatisfiable ranges fail with a distinguishable error.</li>
 * </ul>
 */
class RangeReadTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    // 4 chunks x 10 bytes -> global offsets:
    // part1 [0..9] part2 [10..19] part3 [20..29] part4 [30..39]
    private static final List<byte[]> V1_PARTS = List.of(
            "0123456789".getBytes(StandardCharsets.UTF_8),
            "abcdefghij".getBytes(StandardCharsets.UTF_8),
            "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8),
            "klmnopqrst".getBytes(StandardCharsets.UTF_8));
    private static final byte[] V1_BYTES = "0123456789abcdefghijABCDEFGHIJklmnopqrst".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "ranged.bin", V1_PARTS);
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "ranged.bin",
                List.of(StorageTestSupport.utf8("the-brand-new-v2-content")));
    }

    private byte[] read(String rangeHeader) throws Exception {
        RangeReadResult r = stack.service().readObjectRange("ranged.bin", rangeHeader);
        try (InputStream in = r.content()) {
            return in.readAllBytes();
        }
    }

    private byte[] readVersion(long v, String rangeHeader) throws Exception {
        RangeReadResult r = stack.service().readVersionRange("ranged.bin", v, rangeHeader);
        try (InputStream in = r.content()) {
            return in.readAllBytes();
        }
    }

    @Test
    void fullReadsOfCurrentAndHistoricalVersionsAreCompleteAndExact() throws Exception {
        assertArrayEquals(StorageTestSupport.utf8("the-brand-new-v2-content"), read(null));
        assertArrayEquals(V1_BYTES, readVersion(1, null));
        // current also readable via the version-specific endpoint
        assertArrayEquals(StorageTestSupport.utf8("the-brand-new-v2-content"), readVersion(2, null));

        // missing version and missing object stay distinguishable
        assertThrows(UploadNotFoundException.class, () -> stack.service().readVersionRange("ranged.bin", 9, null));
        assertThrows(UploadNotFoundException.class, () -> stack.service().readObjectRange("nope.bin", null));
        System.out.println("[read] full reads: v2 current and v1 historical both complete and byte-exact");
    }

    @Test
    void rangeFormsReturnExactSlicesIncludingAcrossChunkBoundaries() throws Exception {
        // All slice math below targets the historical v1 (4 chunks of 10 bytes).
        // start-end contained within one chunk
        assertArrayEquals("34567".getBytes(StandardCharsets.UTF_8), readVersion(1, "bytes=3-7"));
        // start-only: to the end of the version
        assertArrayEquals(new String(V1_BYTES, 35, 5).getBytes(StandardCharsets.UTF_8),
                readVersion(1, "bytes=35-"));
        // suffix counted from the end
        assertArrayEquals(new String(V1_BYTES, 30, 10).getBytes(StandardCharsets.UTF_8),
                readVersion(1, "bytes=-10"));
        // exact chunk boundary
        assertArrayEquals("9a".getBytes(StandardCharsets.UTF_8), readVersion(1, "bytes=9-10"));
        // range spanning THREE chunks: 5..25
        byte[] cross = readVersion(1, "bytes=5-25");
        assertArrayEquals(new String(V1_BYTES, 5, 21).getBytes(StandardCharsets.UTF_8), cross);
        assertEquals(21, cross.length);
        // whole object expressed as a range
        assertArrayEquals(V1_BYTES, readVersion(1, "bytes=0-39"));
        // same semantics on the current version (v2, single chunk)
        assertArrayEquals(StorageTestSupport.utf8("bra"), read("bytes=4-6"));
        assertArrayEquals(StorageTestSupport.utf8("content"), read("bytes=-7"));

        // result carries the matched inclusive range + the SAME version snapshot
        RangeReadResult meta = stack.service().readVersionRange("ranged.bin", 1, "bytes=5-25");
        assertEquals(1, meta.version().version());
        assertEquals(5, meta.range().start());
        assertEquals(25, meta.range().end());
        try (InputStream in = meta.content()) {
            assertArrayEquals(new String(V1_BYTES, 5, 21).getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        System.out.println("[read] range forms (start-end/start-only/suffix/cross-chunk) all exact on v1 and v2");
    }

    @Test
    void illegalAndOverflowingRangesFailWithDistinctError() {
        assertThrows(InvalidRangeException.class, () -> read("bytes=40-50"));   // past end
        assertThrows(InvalidRangeException.class, () -> read("bytes=0-40"));    // end inclusive overflow
        assertThrows(InvalidRangeException.class, () -> read("bytes=-0"));      // empty suffix
        assertThrows(InvalidRangeException.class, () -> read("bytes=10-5"));    // reversed
        assertThrows(InvalidRangeException.class, () -> read("bytes=0-9,20-29")); // multi-range
        System.out.println("[read] invalid/overflow ranges -> InvalidRangeException (no empty/whole-object fallback)");
    }

    @Test
    void crossChunkRangeVerifiesEveryPartItTouchesAndFailsOnTampering() throws Exception {
        // Baseline: healthy read works.
        byte[] good = readVersion(1, "bytes=5-25"); // touches parts 1,2,3
        assertEquals(21, good.length);

        // Tamper with part 3 bytes (globally 20..29) — covered by the range.
        Path blobDir = tempDir.resolve("blobs");
        String part3Checksum = stack.service().getVersion("ranged.bin", 1).partChecksums().get(2);
        Path part3 = blobDir.resolve(part3Checksum + ".dat");
        byte[] original = Files.readAllBytes(part3);
        byte[] corrupted = original.clone();
        corrupted[0] = (byte) (corrupted[0] ^ 0xFF);
        Files.write(part3, corrupted);
        try {
            InputStream in = stack.service().readVersionRange("ranged.bin", 1, "bytes=5-25").content();
            ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class, () -> {
                try (InputStream stream = in;
                     ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
                    stream.transferTo(sink); // verification fires on close()
                }
            });
            assertTrue(ex.getMessage().contains("Read-time verification failed"), ex.getMessage());
            System.out.println("[read] tampered part inside range rejected: " + ex.getMessage());
        } finally {
            Files.write(part3, original); // restore for subsequent assertions
        }

        // Healthy again after restoring the blob content.
        assertArrayEquals(good, readVersion(1, "bytes=5-25"));
        System.out.println("[read] verification restored once blob bytes are correct again");
    }

    @Test
    void fullReadVerifiesAllParts() throws Exception {
        // Tamper with the LAST part while reading the whole version: must fail, not truncate.
        StoredObject v1 = stack.service().getVersion("ranged.bin", 1);
        Path lastBlob = tempDir.resolve("blobs").resolve(v1.partChecksums().get(3) + ".dat");
        byte[] original = Files.readAllBytes(lastBlob);
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] = (byte) (corrupted[corrupted.length - 1] ^ 0xFF);
        Files.write(lastBlob, corrupted);
        InputStream in = stack.service().readVersionRange("ranged.bin", 1, null).content();
        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class, () -> {
            try (InputStream stream = in;
                 ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
                stream.transferTo(sink); // full-part verification fires on close()
            }
        });
        assertTrue(ex.getMessage().contains("Read-time verification failed"), ex.getMessage());
        System.out.println("[read] full read verifies every part; tampering fails the read");
        Files.write(lastBlob, original);
        assertArrayEquals(V1_BYTES, readVersion(1, null));
    }

    @Test
    void readSnapshotStaysConsistentWhileNewVersionsArePublished() throws Exception {
        // Pin a v1 snapshot, then publish v3 concurrently-ish; the pinned read must stay v1.
        RangeReadResult pinned = stack.service().readVersionRange("ranged.bin", 1, null);
        StoredObject third = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "ranged.bin", List.of(StorageTestSupport.utf8("v3-content-comes-after-snapshot")));
        assertEquals(3, third.version());
        try (InputStream in = pinned.content()) {
            assertArrayEquals(V1_BYTES, in.readAllBytes());
            assertEquals(1, pinned.version().version(), "bytes and version must originate from the same snapshot");
        }
        // a fresh current read observes the newest version
        assertArrayEquals(StorageTestSupport.utf8("v3-content-comes-after-snapshot"), read(null));
        System.out.println("[read] pinned snapshot v1 unaffected by later publication; new reads see v3");
    }

    @Test
    void noRangeHeaderMeansNullRangeAndFullObject() throws Exception {
        RangeReadResult r = stack.service().readObjectRange("ranged.bin", null);
        assertNull(r.range());
        try (InputStream in = r.content()) {
            assertArrayEquals(StorageTestSupport.utf8("the-brand-new-v2-content"), in.readAllBytes());
        }
    }
}
