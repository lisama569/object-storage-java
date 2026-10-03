package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content-level duplicate detection.
 * <p>
 * Identity is the SHA-256 of the raw object bytes ({@code contentChecksum}), independent of the
 * multipart layout. The decision rule under test:
 * <ul>
 *     <li>identical bytes re-uploaded with a DIFFERENT part count/size -> reuse, no new version,
 *     no extra blob storage;</li>
 *     <li>the reported {@code deduplicated} flag exactly matches reality: true only when the bytes
 *     equal an existing readable version's bytes;</li>
 *     <li>genuinely changed bytes always publish a new version and are never called duplicates;</li>
 *     <li>blobs are content-addressed, so identical byte blocks are shared across objects too.</li>
 * </ul>
 */
class ContentDeduplicationTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;
    private long initialBlobs;

    @BeforeEach
    void setUp() throws Exception {
        stack = StorageTestSupport.newStack(tempDir);
        // v1 layout A: one 30-byte part.
        byte[] content = "0123456789abcdefghijABCDEFGHIJ".getBytes();
        StoredObject first = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", List.of(content));
        this.first = first;
        this.content = content;
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            initialBlobs = blobs.count();
        }
        assertEquals(1, initialBlobs, "single-part upload materialises exactly one blob");
    }

    private StoredObject first;
    private byte[] content;

    /** Re-split the same 30 bytes into different boundaries. */
    private List<byte[]> rechunked(int... sizes) {
        List<byte[]> parts = new ArrayList<>();
        int pos = 0;
        for (int size : sizes) {
            parts.add(java.util.Arrays.copyOfRange(content, pos, pos + size));
            pos += size;
        }
        assertEquals(content.length, pos, "test helper must cover the whole content");
        return parts;
    }

    private long blobCount() throws Exception {
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            return blobs.count();
        }
    }

    @Test
    void sameBytesDifferentPartLayoutReusesVersionAndStorage() throws Exception {
        // Layout B: three 10-byte parts instead of one 30-byte part.
        StoredObject second = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", rechunked(10, 10, 10));
        // Layout C: 4 uneven parts.
        StoredObject third = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", rechunked(3, 7, 15, 5));

        assertEquals(first.version(), second.version(),
                "re-chunked identical bytes must reuse the existing version");
        assertEquals(first.version(), third.version());
        assertTrue(second.deduplicated(), "duplicate conclusion must be reported honestly (re-chunked)");
        assertTrue(third.deduplicated());
        // The reused version metadata keeps the ORIGINAL part layout — bytes were never copied again.
        assertEquals(1, second.partChecksums().size());
        assertEquals(initialBlobs, blobCount(),
                "no extra storage may be used when content is unchanged (even with new part boundaries)");
        System.out.printf("[dedup-rechunk] contentChecksum=%s reused=v%d across layouts [30], [10x3], [3,7,15,5]; blobs=%d%n",
                second.contentChecksum(), second.version(), blobCount());
    }

    @Test
    void changedContentIsNeverReportedDuplicateAndPublishesNewVersion() throws Exception {
        byte[] changed = "0123456789abcdefghijABCDEFGHIx".getBytes(); // last byte differs

        StoredObject evolved = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj", List.of(changed));
        assertEquals(first.version() + 1, evolved.version(), "changed bytes must create a new version");
        assertFalse(evolved.deduplicated(), "changed bytes must not be reported as duplicate");
        assertEquals(1, evolved.partChecksums().size());
        assertTrue(blobCount() >= 1);
        System.out.printf("[dedup-decision] content changed contentChecksum %s -> %s; publish v2, deduplicated=false%n",
                first.contentChecksum(), evolved.contentChecksum());
    }

    @Test
    void reuploadOfHistoricalVersionContentIsReusedWithoutNewVersion() {
        // A, then B (B current), then A again -> A's bytes still exist as readable v1, reuse v1.
        byte[] bytesA = "content-AAAAAAAAAAAAAAAAAAAA".getBytes();
        byte[] bytesB = "content-BBBBBBBBBBBBBBBBBBBBB".getBytes();
        StoredObject a = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "evo", List.of(bytesA));
        StoredObject b = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "evo", List.of(bytesB));
        assertEquals(2, b.version());

        // Same bytes as A, but using a different split than A originally used.
        StoredObject backToA = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "evo", List.of(
                        java.util.Arrays.copyOfRange(bytesA, 0, 8),
                        java.util.Arrays.copyOfRange(bytesA, 8, bytesA.length)));
        assertEquals(a.version(), backToA.version(),
                "returning to an old version's bytes reuses that version; current never moves backwards");
        assertTrue(backToA.deduplicated());
        assertEquals(2, stack.service().headObject("evo").version(),
                "current pointer stays at v2 (the reused v1 is historical, current never rolls back)");
        System.out.printf("[dedup-history] A=v1 B=v2 A'(re-chunked)->reuse v1; current remains v2; checksum=%s%n",
                backToA.contentChecksum());
    }

    @Test
    void identicalBlobBytesAreSharedAcrossObjects() throws Exception {
        // A different object name is its own version line (first version, not a "duplicate" of that
        // object), but content-addressing still stores zero new blobs because the bytes already exist.
        StoredObject other = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "obj-copy", List.of(content));
        assertEquals(1, other.version(), "a new object starts its own version line");
        assertFalse(other.deduplicated(),
                "duplicate=false is honest: this object had no prior version with those bytes");
        assertEquals(initialBlobs, blobCount(),
                "identical content under another object must reuse the existing blob (no new storage)");
        System.out.printf("[dedup-cross-object] obj-copy v1 reuses existing blob; deduplicated=false "
                + "but blobCount stays %d, contentChecksum=%s%n", blobCount(), other.contentChecksum());
    }
}
