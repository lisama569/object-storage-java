package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.RangeReadResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-path regression coverage:
 * <ul>
 *   <li>latest and historical versions always return the full, byte-exact content;</li>
 *   <li>ranges accept start-end / start-only / suffix spellings, including windows that cross
 *       part boundaries (integrity verification is never skipped across parts);</li>
 *   <li>malformed ranges (400) and unsatisfiable ranges (416) are distinguishable and never
 *       silently return empty or the whole object;</li>
 *   <li>one read always pairs version metadata with that same version's bytes even while other
 *       threads publish new versions.</li>
 * </ul>
 */
class ReadPipelineTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    /** 3 parts of distinct sizes, concatenation = "0123456789ABCDEFGHIJKLMNOPQRSTUV" (32 bytes). */
    private final List<byte[]> v1Parts = List.of(
            StorageTestSupport.utf8("0123456789"),       // 10 bytes, offsets 0..9
            StorageTestSupport.utf8("ABCDEFGHIJKL"),     // 12 bytes, offsets 10..21
            StorageTestSupport.utf8("MNOPQRSTUV"));      // 10 bytes, offsets 22..31
    private final byte[] v1Bytes = StorageTestSupport.concat(v1Parts);

    private final List<byte[]> v2Parts = List.of(
            StorageTestSupport.utf8("new-content-version-two!!"));
    private final byte[] v2Bytes = StorageTestSupport.concat(v2Parts);

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
    }

    @Test
    void fullReadOfLatestAndHistoricalVersionIsByteExact() throws Exception {
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", v1Parts);
        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", v2Parts);
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());

        assertArrayEquals(v2Bytes, StorageTestSupport.readAll(stack.service().readObject("obj")),
                "latest read must return the full current bytes");
        assertArrayEquals(v1Bytes, StorageTestSupport.readAll(stack.service().readVersion("obj", 1)),
                "historical read must return the full old bytes");
        System.out.printf("[read] full read latest=v2(%d bytes), history=v1(%d bytes)%n",
                v2Bytes.length, v1Bytes.length);
    }

    @Test
    void startEndRangeCrossingTwoPartsIsVerifiedAndExact() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);
        // Window 7..15 crosses part1 (7..9) and part2 (10..15) -> "789ABCDEF"
        RangeReadResult r = stack.service().readObjectRange("obj", "bytes=7-15");
        assertEquals(1, r.version().version(), "range result must carry the resolved version snapshot");
        assertEquals(7, r.range().start());
        assertEquals(15, r.range().end());
        byte[] got = StorageTestSupport.readAll(r.content());
        assertArrayEquals(Arrays.copyOfRange(v1Bytes, 7, 16), got);
        assertEquals("789ABCDEF", new String(got, java.nio.charset.StandardCharsets.UTF_8));
        System.out.printf("[range-read] bytes=7-15 across parts -> '%s' version=v%d (both blobs verified)%n",
                new String(got), r.version().version());
    }

    @Test
    void rangeCrossingAllThreeParts() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);
        RangeReadResult r = stack.service().readObjectRange("obj", "bytes=5-27");
        byte[] got = StorageTestSupport.readAll(r.content());
        assertArrayEquals(Arrays.copyOfRange(v1Bytes, 5, 28), got);
        System.out.printf("[range-read] bytes=5-27 spans all 3 parts -> %d exact bytes%n", got.length);
    }

    @Test
    void startOnlyAndSuffixRangesWork() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);

        RangeReadResult open = stack.service().readObjectRange("obj", "bytes=28-");
        byte[] openBytes = StorageTestSupport.readAll(open.content());
        assertArrayEquals(Arrays.copyOfRange(v1Bytes, 28, 32), openBytes);
        assertEquals(31, open.range().end());
        System.out.println("[range-read] bytes=28- -> "
                + new String(openBytes, java.nio.charset.StandardCharsets.UTF_8));

        RangeReadResult suffix = stack.service().readObjectRange("obj", "bytes=-6");
        assertArrayEquals(Arrays.copyOfRange(v1Bytes, 26, 32), StorageTestSupport.readAll(suffix.content()));
        assertEquals(26, suffix.range().start());
        System.out.println("[range-read] bytes=-6 (suffix crossing parts) -> "
                + new String(Arrays.copyOfRange(v1Bytes, 26, 32), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rangeReadOfHistoricalVersionServesThatVersionsBytes() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v2Parts);
        RangeReadResult r = stack.service().readVersionRange("obj", 1, "bytes=8-13");
        assertEquals(1, r.version().version());
        assertArrayEquals(Arrays.copyOfRange(v1Bytes, 8, 14), StorageTestSupport.readAll(r.content()));
        System.out.printf("[range-read] v1 bytes=8-13 after v2 published -> exact %d bytes of v1%n",
                r.range().length());
    }

    @Test
    void invalidAndUnsatisfiableRangesAreDistinguishable() {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);
        // 400: malformed — must not return empty or full content.
        InvalidRangeException bad = assertThrows(InvalidRangeException.class,
                () -> stack.service().readObjectRange("obj", "bytes=oops"));
        assertFalse(bad instanceof RangeNotSatisfiableException);
        System.out.println("[range-reject] malformed -> 400 invalid_range: " + bad.getMessage());
        // 416: well-formed but beyond the version size.
        assertThrows(RangeNotSatisfiableException.class,
                () -> stack.service().readObjectRange("obj", "bytes=32-"));
        assertThrows(RangeNotSatisfiableException.class,
                () -> stack.service().readVersionRange("obj", 1, "bytes=100-200"));
        System.out.println("[range-reject] bytes=32- of size 32 -> 416 range_not_satisfiable");
        // A removed version still yields 404 rather than any bytes.
        assertThrows(UploadNotFoundException.class,
                () -> stack.service().readVersionRange("obj", 99, "bytes=0-0"));
    }

    @Test
    void readsRemainSelfConsistentWhileVersionsArePublishedConcurrently() throws Exception {
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "obj", v1Parts);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                int v2Observations = 0;
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline && v2Observations < 50
                        && failure.get() == null) {
                    // Resolve ONE snapshot and open ITS bytes; headers/content must match exactly.
                    StoredObject head = stack.service().headObject("obj");
                    byte[] bytes;
                    try (var in = stack.service().openVersion(head)) {
                        bytes = in.readAllBytes();
                    }
                    if (head.version() == 1 && !Arrays.equals(bytes, v1Bytes)) {
                        throw new AssertionError("v1 read torn: got " + bytes.length + " bytes");
                    }
                    if (head.version() == 2 && !Arrays.equals(bytes, v2Bytes)) {
                        throw new AssertionError("v2 read torn or served another version's bytes");
                    }
                    // A range read must pair with the same snapshot version and be exact.
                    RangeReadResult rr = stack.service().readObjectRange("obj", "bytes=0-4");
                    byte[] window;
                    try (var in = rr.content()) {
                        window = in.readAllBytes();
                    }
                    byte[] base = rr.version().version() == 1 ? v1Bytes : v2Bytes;
                    byte[] expect = Arrays.copyOfRange(base, 0, 5);
                    if (!Arrays.equals(window, expect)) {
                        throw new AssertionError("range window mismatches snapshot v"
                                + rr.version().version());
                    }
                    if (rr.version().version() != head.version()
                            && rr.version().version() < head.version()) {
                        throw new AssertionError("snapshot moved backwards");
                    }
                    if (head.version() == 2) {
                        v2Observations++;
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();

        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", v2Parts);
        // Let the reader observe the published v2 a number of times, rather than a fixed wall time.
        for (int i = 0; i < 400 && reader.isAlive(); i++) {
            if (stack.service().headObject("obj").version() == 2) {
                Thread.sleep(5);
            }
        }
        reader.join(5_000);
        assertEquals(null, failure.get(),
                failure.get() == null ? "" : failure.get().getMessage());
        assertTrue(v2.version() == 2);
        System.out.println("[consistency] concurrent reads while publishing v2: every full/range read"
                + " matched its own version snapshot");
    }
}
