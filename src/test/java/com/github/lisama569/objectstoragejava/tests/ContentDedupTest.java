package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.ManifestEntry;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunking-independent duplicate detection.
 * <p>
 * The duplicate decision is: SHA-256 over the fully assembled raw BYTES (plus exact size)
 * compared against the current version. Part count and per-part sizes are irrelevant, and the
 * decision ({@code deduplicated}) plus its explanation ({@code dedupBasis}) are printed so the
 * result is reproducible from the log.
 */
class ContentDedupTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    private static final byte[] CONTENT =
            "the-quick-brown-fox-jumps-over-the-lazy-dog-0123456789".getBytes(StandardCharsets.UTF_8);

    /** Split the same bytes into differently sized chunks (boundaries are absolute offsets). */
    private static List<byte[]> chunk(int... boundaries) {
        List<byte[]> parts = new ArrayList<>();
        int from = 0;
        for (int b : boundaries) {
            parts.add(Arrays.copyOfRange(CONTENT, from, b));
            from = b;
        }
        parts.add(Arrays.copyOfRange(CONTENT, from, CONTENT.length));
        return parts;
    }

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
    }

    @Test
    void sameBytesDifferentChunkingReusesVersionAndBlobs() throws Exception {
        // 1 chunk vs 3 chunks vs 7 chunks — identical assembled bytes.
        List<List<byte[]>> layouts = List.of(
                List.of(CONTENT),
                chunk(17, 33),
                chunk(5, 11, 19, 23, 37, 41)
        );

        StoredObject v1 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "obj", layouts.get(0));
        String expectedContentChecksum = StorageTestSupport.sha256(stack.checksums(), CONTENT);
        assertEquals(expectedContentChecksum, v1.contentChecksum());
        assertFalse(v1.deduplicated(), "first upload is a genuine publish");
        long blobsAfterFirst = countBlobs();

        for (int round = 1; round < layouts.size(); round++) {
            StoredObject again = StorageTestSupport.multipartUploadWithContentChecksum(
                    stack.service(), stack.checksums(), "obj", layouts.get(round));
            assertEquals(1, again.version(),
                    "re-chunking identical bytes must not create a version (round " + round + ")");
            assertTrue(again.deduplicated(), "decision must report duplicate (round " + round + ")");
            assertEquals(expectedContentChecksum, again.contentChecksum());
            assertEquals(v1.checksum(), again.checksum());
            assertTrue(again.dedupBasis().contains(expectedContentChecksum),
                    "dedup basis must explain via the assembled-byte digest");
            System.out.printf("[dedup-chunk] round=%d chunkCount=%d decision=duplicate version=%d basis=%s%n",
                    round, layouts.get(round).size(), again.version(), again.dedupBasis());
        }
        assertEquals(blobsAfterFirst, countBlobs(), "no new blob storage from re-chunked identical uploads");
        System.out.printf("[dedup-chunk] blobs=%d unchanged after %d different chunkings of the same %d bytes%n",
                blobsAfterFirst, layouts.size(), CONTENT.length);

        assertArrayEquals(CONTENT, stack.service().readObject("obj").readAllBytes());
    }

    @Test
    void genuinelyDifferentContentIsNeverReportedAsDuplicate() {
        StoredObject v1 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "obj", List.of(CONTENT));
        byte[] changed = CONTENT.clone();
        changed[10] = (byte) (changed[10] ^ 0x01); // one bit difference, same length
        StoredObject v2 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "obj", List.of(changed));

        assertEquals(2, v2.version(), "changed content must publish a new version");
        assertFalse(v2.deduplicated(), "changed content must never be reported duplicate");
        assertNotEquals(v1.contentChecksum(), v2.contentChecksum());
        assertTrue(v2.dedupBasis().contains("differs from current"));
        System.out.printf("[dedup-chunk] decision=new version: %s%n", v2.dedupBasis());
    }

    @Test
    void claimedContentChecksumIsVerifiedAndMismatchRejected() {
        List<byte[]> parts = chunk(17, 33);
        String objectCs = stack.checksums().combinePartChecksums(
                parts.stream().map(p -> StorageTestSupport.sha256(stack.checksums(), p)).toList());
        UploadInitResponse init = stack.service().initUpload("obj");
        List<ManifestEntry> entries = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String cs = StorageTestSupport.sha256(stack.checksums(), parts.get(i));
            stack.service().uploadPart("obj", init.uploadId(), i + 1, cs,
                    new ByteArrayInputStream(parts.get(i)));
            entries.add(new ManifestEntry(i + 1, cs));
        }
        String liar = "1".repeat(64);
        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> stack.service().completeUpload("obj", init.uploadId(),
                        new ManifestRequest(objectCs, entries, liar)));
        assertTrue(ex.getMessage().contains("Content checksum mismatch"), ex.getMessage());
        System.out.println("[dedup-chunk] wrong contentChecksum claim rejected: " + ex.getMessage());
    }

    @Test
    void contentEqualToAnOlderButNotCurrentVersionStillPublishes() {
        byte[] a = "payload-A".getBytes(StandardCharsets.UTF_8);
        byte[] b = "payload-B-different".getBytes(StandardCharsets.UTF_8);
        StoredObject v1 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "osc", List.of(a));
        StoredObject v2 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "osc", List.of(b));
        // Re-upload v1's bytes: current is v2, so this is genuinely new relative to current -> v3.
        StoredObject v3 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "osc", List.of(a));
        assertEquals(3, v3.version(), "reuse is only allowed against the CURRENT version, never an older one");
        assertFalse(v3.deduplicated());
        assertEquals(v1.contentChecksum(), v3.contentChecksum());
        // v3 bytes are A, and both older versions remain readable.
        assertArrayEquals(a, readAll("osc", 3));
        assertArrayEquals(b, readAll("osc", 2));
        assertArrayEquals(a, readAll("osc", 1));
        System.out.printf("[dedup-chunk] same-as-old-v1 but current=v2 -> publish v3; dedup only vs current%n");
    }

    private byte[] readAll(String name, long version) {
        try (var in = stack.service().readVersion(name, version)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long countBlobs() {
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            return blobs.count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
