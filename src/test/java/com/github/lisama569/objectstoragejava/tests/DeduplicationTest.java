package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplicate-upload decision contract:
 * <ul>
 *   <li>the decision is based ONLY on SHA-256 of the assembled object bytes, never on part
 *       count or part boundaries — re-splitting identical bytes into different chunks reuses
 *       the existing version and adds no blob storage;</li>
 *   <li>actually different bytes always publish a new version even when the chunking matches;</li>
 *   <li>the response's {@code deduplicated} flag matches reality and {@code dedupBasis} states
 *       exactly how the conclusion was reached (digests compared), so it is reproducible by
 *       re-hashing the bytes;</li>
 *   <li>a wrong claimed content checksum is rejected before any publication.</li>
 * </ul>
 */
class DeduplicationTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    private long blobCount() throws Exception {
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            return blobs.count();
        }
    }

    /** Client-side recomputation of the chunk-layout-dependent chained object checksum. */
    private String chainedChecksum(List<byte[]> parts) {
        List<String> partChecksums = parts.stream()
                .map(p -> StorageTestSupport.sha256(stack.checksums(), p)).toList();
        return stack.checksums().combinePartChecksums(partChecksums);
    }

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
    }

    @Test
    void identicalBytesWithDifferentChunkingReuseVersionAndAddNoStorage() throws Exception {
        byte[] content = StorageTestSupport.utf8("the-quick-brown-fox-jumps-over-the-lazy-dog-0123456789");

        // First upload: split into 3 uneven parts.
        List<byte[]> chunkingA = List.of(
                java.util.Arrays.copyOfRange(content, 0, 7),
                java.util.Arrays.copyOfRange(content, 7, 30),
                java.util.Arrays.copyOfRange(content, 30, content.length));
        String chainedA = chainedChecksum(chunkingA);
        StoredObject first = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "rechunked.bin", chunkingA);
        assertEquals(1, first.version());
        assertFalse(first.deduplicated());
        String contentChecksum = StorageTestSupport.sha256(stack.checksums(), content);
        assertEquals(contentChecksum, first.contentChecksum());
        long blobsAfterFirst = blobCount();
        assertEquals(3, blobsAfterFirst, "three unique part chunks -> three blobs");

        // Second upload of the SAME bytes, re-split completely differently: one single part.
        List<byte[]> chunkingB = List.of(content);
        String chainedB = chainedChecksum(chunkingB);
        StoredObject second = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "rechunked.bin", chunkingB);
        assertEquals(1, second.version(), "re-chunked identical bytes must NOT create a version");
        assertTrue(second.deduplicated(), "response must truthfully report a duplicate upload");
        assertEquals(blobsAfterFirst, blobCount(), "re-chunked duplicate must add no blob storage");
        org.junit.jupiter.api.Assertions.assertNotEquals(chainedA, chainedB,
                "the legacy chained checksum must change when the part layout changes");
        System.out.printf("[dedup-rechunk] v1 reused; chunk-layout-dependent chained checksum differs %s"
                        + " (3 parts) vs %s (1 part), but contentChecksum=%s is identical%n",
                chainedA, chainedB, second.contentChecksum());
        System.out.println("[dedup-rechunk] basis: " + second.dedupBasis());

        // Third upload: two parts at yet another boundary.
        List<byte[]> chunkingC = List.of(
                java.util.Arrays.copyOfRange(content, 0, 25),
                java.util.Arrays.copyOfRange(content, 25, content.length));
        StoredObject third = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "rechunked.bin", chunkingC);
        assertEquals(1, third.version());
        assertTrue(third.deduplicated());
        assertEquals(blobsAfterFirst, blobCount());
        System.out.printf("[dedup-rechunk] decision reproducible across 3 chunkings: always v1, blobs=%d%n",
                blobCount());
    }

    @Test
    void differentBytesWithSameChunkingPublishNewVersion() throws Exception {
        byte[] one = StorageTestSupport.utf8("payload-version-1");
        byte[] two = StorageTestSupport.utf8("payload-version-2-different");

        StoredObject v1 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "data", List.of(one));
        StoredObject v2 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "data", List.of(two));
        // And a third single-part upload identical to v1 bytes: v1 is no longer current, so this
        // is genuinely a new content change relative to current v2 -> publish v3 (no rollback),
        // but v3 shares v1's blob because the bytes are content-identical.
        StoredObject v3 = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "data", List.of(one));

        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertFalse(v2.deduplicated(), "changed content must never be reported as duplicate");
        assertNotEquals(v1.contentChecksum(), v2.contentChecksum());
        assertEquals(3, v3.version(), "re-introducing old bytes relative to current publishes a new"
                + " forward version; the pointer never rolls back");
        assertFalse(v3.deduplicated());
        assertEquals(v1.contentChecksum(), v3.contentChecksum());
        byte[] v3Bytes = StorageTestSupport.readAll(stack.service().readVersion("data", 3));
        assertTrue(java.util.Arrays.equals(one, v3Bytes), "v3 must contain exactly v1's bytes");
        System.out.println("[dedup-differs] " + v2.dedupBasis());
        System.out.printf("[dedup-differs] v1(cs=%s) v2(cs=%s) v3(cs=%s); blobs=%d (v3 shares v1's blob)%n",
                v1.contentChecksum(), v2.contentChecksum(), v3.contentChecksum(), blobCount());
        assertEquals(2, blobCount(), "two unique byte contents -> two blobs regardless of versions");
    }

    @Test
    void wrongClaimedContentChecksumIsRejected() {
        byte[] content = StorageTestSupport.utf8("trust-but-verify");
        String partCs = StorageTestSupport.sha256(stack.checksums(), content);
        UploadInitResponse init = stack.service().initUpload("lied");
        stack.service().uploadPart("lied", init.uploadId(), 1, partCs,
                new ByteArrayInputStream(content));
        String chained = stack.checksums().combinePartChecksums(List.of(partCs));
        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> stack.service().completeUpload("lied", init.uploadId(),
                        new com.github.lisama569.objectstoragejava.dto.ManifestRequest(
                                chained, List.of(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(1, partCs)),
                                "f".repeat(64))));
        assertTrue(ex.getMessage().contains("Content checksum mismatch"));
        assertThrows(com.github.lisama569.objectstoragejava.exception.UploadNotFoundException.class,
                () -> stack.service().headObject("lied"));
        System.out.println("[dedup-reject] claimed contentChecksum verified server-side -> "
                + ex.getMessage());
    }

    @Test
    void legacyVersionWithoutContentChecksumFieldStillDedupsByActualBytes() throws Exception {
        byte[] content = StorageTestSupport.utf8("legacy-written-version-bytes");
        // Publish normally so the content-addressed blob exists, then rewrite v1.json into the
        // OLD shape that lacks contentChecksum/partSizes/dedupBasis (what the previous build wrote).
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(),
                "legacy", List.of(content));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path versionsDir = tempDir.resolve("objects/legacy/versions");
        Path vFile = versionsDir.resolve("v1.json");
        String legacyJson = mapper.writeValueAsString(java.util.Map.of(
                "objectName", "legacy",
                "version", 1,
                "checksum", v1.checksum(),
                "size", v1.size(),
                "partChecksums", v1.partChecksums(),
                "deduplicated", false));
        Files.writeString(vFile, legacyJson, java.nio.charset.StandardCharsets.UTF_8);
        assertNull(stack.service().headObject("legacy").contentChecksum(),
                "rewritten metadata must look like an old-build version (no contentChecksum)");

        // Re-upload the identical bytes re-split into two parts; the server derives the legacy
        // version's content checksum on the fly from its immutable blobs and still dedups.
        List<byte[]> resplit = List.of(
                java.util.Arrays.copyOfRange(content, 0, 10),
                java.util.Arrays.copyOfRange(content, 10, content.length));
        StoredObject again = StorageTestSupport.multipartUploadWithContentChecksum(
                stack.service(), stack.checksums(), "legacy", resplit);
        assertEquals(1, again.version());
        assertTrue(again.deduplicated());
        assertTrue(again.dedupBasis().contains("derived"));
        System.out.println("[dedup-legacy] " + again.dedupBasis());
    }
}
