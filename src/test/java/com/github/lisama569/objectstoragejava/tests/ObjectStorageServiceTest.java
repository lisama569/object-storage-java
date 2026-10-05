package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.ChunkUploadResponse;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.InvalidManifestException;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageTestSupport.Stack stack;

    @BeforeEach
    void setUp() {
        stack = StorageTestSupport.newStack(tempDir);
    }

    @Test
    void publishesVersionAndReadsBackExactBytes() throws Exception {
        List<byte[]> parts = List.of(
                StorageTestSupport.utf8("hello-"),
                StorageTestSupport.utf8("world-"),
                StorageTestSupport.utf8("v1"));
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "doc.txt", parts);

        assertEquals(1, v1.version());
        String expectedChecksum = stack.checksums().combinePartChecksums(
                parts.stream().map(p -> StorageTestSupport.sha256(stack.checksums(), p)).toList());
        assertEquals(expectedChecksum, v1.checksum());
        System.out.printf("[version] object=%s published version=%d checksum=%s (decision: first publish)%n",
                "doc.txt", v1.version(), v1.checksum());

        byte[] readBack = StorageTestSupport.readAll(stack.service().readObject("doc.txt"));
        assertArrayEquals(StorageTestSupport.concat(parts), readBack);
        assertEquals("doc.txt", v1.objectName());
    }

    @Test
    void identicalReuploadIsDeduplicatedWithoutNewVersion() {
        List<byte[]> parts = List.of(
                StorageTestSupport.utf8("repeatable-content-"),
                StorageTestSupport.utf8("block-two"));
        StoredObject first = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "dup.bin", parts);
        StoredObject second = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "dup.bin", parts);

        assertEquals(first.version(), second.version(), "same content must not create a new version");
        assertEquals(first.checksum(), second.checksum());
        assertTrue(second.deduplicated(), "the response must explicitly report deduplication");
        System.out.printf("[dedup] identical re-upload decision=reuse-version-%d checksum=%s%n",
                second.version(), second.checksum());

        long blobCount;
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            blobCount = blobs.count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Two unique part contents -> exactly two blobs, regardless of how many times uploaded.
        assertEquals(2, blobCount, "no redundant blob storage for identical content");
        System.out.printf("[dedup] blob directory contains %d unique blobs%n", blobCount);
    }

    @Test
    void newVersionKeepsOldVersionReadable() throws Exception {
        List<byte[]> v1Parts = List.of(StorageTestSupport.utf8("version-one-data"));
        List<byte[]> v2Parts = List.of(StorageTestSupport.utf8("version-two-data"),
                StorageTestSupport.utf8("second-part"));

        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "evolving", v1Parts);
        StoredObject v2 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "evolving", v2Parts);

        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        StoredObject head = stack.service().headObject("evolving");
        assertEquals(2, head.version(), "current pointer must move forward");

        byte[] oldBytes = StorageTestSupport.readAll(stack.service().readVersion("evolving", 1));
        byte[] newBytes = StorageTestSupport.readAll(stack.service().readObject("evolving"));
        assertArrayEquals(StorageTestSupport.concat(v1Parts), oldBytes, "old version must stay readable forever");
        assertArrayEquals(StorageTestSupport.concat(v2Parts), newBytes);
        System.out.printf("[version] decision: content differs v1=%s -> publish v2=%s; v1 still readable%n",
                v1.checksum(), v2.checksum());
    }

    @Test
    void rejectsPartWithWrongChecksum() {
        UploadInitResponse init = stack.service().initUpload("bad-part");
        byte[] part = StorageTestSupport.utf8("actual bytes");
        String claimed = "0".repeat(64);
        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class, () ->
                stack.service().uploadPart("bad-part", init.uploadId(), 1, claimed,
                        new ByteArrayInputStream(part)));
        assertTrue(ex.getMessage().contains("checksum mismatch"));
        System.out.println("[reject] wrong part checksum -> " + ex.getMessage());
    }

    @Test
    void rejectsCompletionWhenManifestReferencesMissingPart() {
        UploadInitResponse init = stack.service().initUpload("incomplete");
        byte[] part = StorageTestSupport.utf8("only-one-part");
        String checksum = StorageTestSupport.sha256(stack.checksums(), part);
        stack.service().uploadPart("incomplete", init.uploadId(), 1, checksum, new ByteArrayInputStream(part));

        // Claim parts 1..2 while part 2 was never uploaded.
        var manifest = new com.github.lisama569.objectstoragejava.dto.ManifestRequest(
                stack.checksums().combinePartChecksums(List.of(checksum, checksum)),
                List.of(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(1, checksum),
                        new com.github.lisama569.objectstoragejava.dto.ManifestEntry(2, checksum)));
        InvalidManifestException ex = assertThrows(InvalidManifestException.class,
                () -> stack.service().completeUpload("incomplete", init.uploadId(), manifest));
        assertTrue(ex.getMessage().contains("has not been uploaded"),
                "incomplete references must be rejected with a distinguishable reason");
        System.out.println("[reject] missing referenced part -> " + ex.getMessage());
        assertThrows(UploadNotFoundException.class, () -> stack.service().headObject("incomplete"),
                "nothing must be committed when the manifest is invalid");
    }

    @Test
    void rejectsObjectChecksumMismatchAndKeepsOldVersion() {
        List<byte[]> v1Parts = List.of(StorageTestSupport.utf8("stable-old-content"));
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "guard", v1Parts);

        UploadInitResponse init = stack.service().initUpload("guard");
        byte[] part = StorageTestSupport.utf8("new-content-but-wrong-manifest");
        String partChecksum = StorageTestSupport.sha256(stack.checksums(), part);
        stack.service().uploadPart("guard", init.uploadId(), 1, partChecksum, new ByteArrayInputStream(part));

        var manifest = new com.github.lisama569.objectstoragejava.dto.ManifestRequest(
                "f".repeat(64),
                List.of(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(1, partChecksum)));
        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> stack.service().completeUpload("guard", init.uploadId(), manifest));
        System.out.println("[reject] wrong object checksum -> " + ex.getMessage());

        StoredObject stillCurrent = stack.service().headObject("guard");
        assertEquals(v1.version(), stillCurrent.version(), "failed publish must not move the version pointer");
        assertEquals(v1.checksum(), stillCurrent.checksum(), "old version content must remain intact");
        System.out.printf("[recovery] failed commit leaves current=v%d checksum=%s%n",
                stillCurrent.version(), stillCurrent.checksum());
    }

    @Test
    void interruptedUploadCanResumeAndComplete() {
        List<byte[]> parts = List.of(
                StorageTestSupport.utf8("resume-p0-"),
                StorageTestSupport.utf8("resume-p1-"),
                StorageTestSupport.utf8("resume-p2"));

        UploadInitResponse init = stack.service().initUpload("resume.bin");
        // Upload parts 1 and 2, then the client "dies" before completing.
        String cs1 = StorageTestSupport.sha256(stack.checksums(), parts.get(0));
        String cs2 = StorageTestSupport.sha256(stack.checksums(), parts.get(1));
        stack.service().uploadPart("resume.bin", init.uploadId(), 1, cs1, new ByteArrayInputStream(parts.get(0)));
        stack.service().uploadPart("resume.bin", init.uploadId(), 2, cs2, new ByteArrayInputStream(parts.get(1)));

        // A fresh client queries status and discovers what already landed.
        UploadStatusResponse status = stack.service().uploadStatus("resume.bin", init.uploadId());
        assertEquals(List.of(1, 2), status.parts().stream().map(UploadStatusResponse.PartStatus::partNumber).toList());
        System.out.printf("[resume] after interruption, server already holds parts=%s%n",
                status.parts().stream().map(UploadStatusResponse.PartStatus::partNumber).toList());

        // Re-uploading part 1 must be an idempotent no-op, then the missing part 3 is sent.
        ChunkUploadResponse repeated = stack.service().uploadPart("resume.bin", init.uploadId(), 1, cs1,
                new ByteArrayInputStream(parts.get(0)));
        assertTrue(repeated.alreadyPresent(), "resumed duplicate part must be reported as already present");

        String cs3 = StorageTestSupport.sha256(stack.checksums(), parts.get(2));
        stack.service().uploadPart("resume.bin", init.uploadId(), 3, cs3, new ByteArrayInputStream(parts.get(2)));

        // Client completes with the full manifest referencing the three stored parts.
        var entries = new ArrayList<com.github.lisama569.objectstoragejava.dto.ManifestEntry>();
        entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(1, cs1));
        entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(2, cs2));
        entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(3, cs3));
        String objectChecksum = stack.checksums().combinePartChecksums(List.of(cs1, cs2, cs3));
        StoredObject published = stack.service().completeUpload("resume.bin", init.uploadId(),
                new com.github.lisama569.objectstoragejava.dto.ManifestRequest(objectChecksum, entries));

        assertEquals(1, published.version());
        assertEquals(objectChecksum, published.checksum());
        System.out.printf("[resume] resumed upload published version=%d checksum=%s (parts 1-3)%n",
                published.version(), published.checksum());
    }

    @Test
    void abortNeverTouchesCommittedVersion() {
        List<byte[]> parts = List.of(StorageTestSupport.utf8("committed-before-abort"));
        StoredObject v1 = StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "abortable", parts);

        UploadInitResponse init = stack.service().initUpload("abortable");
        byte[] part = StorageTestSupport.utf8("will-be-thrown-away");
        String cs = StorageTestSupport.sha256(stack.checksums(), part);
        stack.service().uploadPart("abortable", init.uploadId(), 1, cs, new ByteArrayInputStream(part));
        stack.service().abortUpload("abortable", init.uploadId());

        assertThrows(UploadNotFoundException.class,
                () -> stack.service().uploadStatus("abortable", init.uploadId()));
        assertEquals(v1.checksum(), stack.service().headObject("abortable").checksum());
        System.out.printf("[abort] staging removed, committed v%d checksum=%s remains readable%n",
                v1.version(), v1.checksum());
    }
}
