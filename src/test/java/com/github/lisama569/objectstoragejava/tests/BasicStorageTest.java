package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.ErrorCode;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础上传/下载、分块校验、清单完整性与各类错误码的可区分性。
 */
class BasicStorageTest extends AbstractStorageTest {

    @TempDir
    Path tempDir;

    private ObjectStorageService service() {
        return newService(tempDir.toString());
    }

    @Test
    void multipartUploadThenReadMatchesChecksum() {
        ObjectStorageService svc = service();
        byte[] content = "hello-object-storage-multipart".getBytes();
        CompleteResult result = multipartUpload(svc, "docs/a.txt", content, 7);

        assertFalse(result.deduplicated());
        assertEquals(1, result.manifest().version());
        assertEquals(sha256Hex(content), result.manifest().checksum());
        System.out.println("[basic] v1 checksum=" + result.manifest().checksum()
                + " decision=" + result.decision());

        byte[] read = svc.readCurrentBytes("docs/a.txt");
        assertArrayEquals(content, read);
        assertEquals(sha256Hex(content), svc.currentVersion("docs/a.txt").checksum());
    }

    @Test
    void chunkChecksumMismatchIsRejectedWithDistinctCode() {
        ObjectStorageService svc = service();
        UploadSessionMeta session = svc.initiateUpload("k1");
        byte[] chunk = utf8("abcdef");
        String wrong = "00".repeat(32);
        StorageException ex = assertThrows(StorageException.class, () ->
                svc.uploadPart(session.sessionId(), 1, new ByteArrayInputStream(chunk), wrong));
        assertEquals(ErrorCode.CHUNK_CHECKSUM_MISMATCH, ex.getCode());
        System.out.println("[basic] rejected bad chunk: " + ex.getMessage());

        // 对象不存在（失败的上传不得产生任何版本）
        StorageException notFound = assertThrows(StorageException.class,
                () -> svc.currentVersion("k1"));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND, notFound.getCode());
    }

    @Test
    void missingManifestRejected() {
        ObjectStorageService svc = service();
        UploadSessionMeta session = svc.initiateUpload("k2");
        StorageException ex = assertThrows(StorageException.class, () ->
                svc.completeUpload(session.sessionId(), List.of(), List.of(), "aa".repeat(32)));
        assertEquals(ErrorCode.MANIFEST_MISSING, ex.getCode());
        System.out.println("[basic] rejected missing manifest: " + ex.getMessage());
    }

    @Test
    void incompleteManifestGapRejected() {
        ObjectStorageService svc = service();
        UploadSessionMeta session = svc.initiateUpload("k3");
        byte[] c1 = utf8("part-one");
        byte[] c2 = utf8("part-two");
        StoredChunk p1 = svc.uploadPart(session.sessionId(), 1, new ByteArrayInputStream(c1), sha256Hex(c1));
        StoredChunk p2 = svc.uploadPart(session.sessionId(), 2, new ByteArrayInputStream(c2), sha256Hex(c2));

        // 清单跳过 part1（从 2 开始）
        StorageException gap = assertThrows(StorageException.class, () ->
                svc.completeUpload(session.sessionId(), List.of(2), List.of(p2.checksum()), null));
        assertEquals(ErrorCode.MANIFEST_INCOMPLETE, gap.getCode());

        // 清单引用了从未上传的 part3
        StorageException missingRef = assertThrows(StorageException.class, () ->
                svc.completeUpload(session.sessionId(),
                        List.of(1, 2, 3),
                        List.of(p1.checksum(), p2.checksum(), "bb".repeat(32)), null));
        assertEquals(ErrorCode.MANIFEST_INCOMPLETE, missingRef.getCode());
        System.out.println("[basic] rejected incomplete manifest: " + missingRef.getMessage());
    }

    @Test
    void objectChecksumMismatchRejected() {
        ObjectStorageService svc = service();
        byte[] content = utf8("whole-content-here");
        String wrongObjectChecksum = "cc".repeat(32);
        UploadSessionMeta session = svc.initiateUpload("k4b");
        StoredChunk p = svc.uploadPart(session.sessionId(), 1,
                new ByteArrayInputStream(content), sha256Hex(content));
        StorageException mismatch = assertThrows(StorageException.class, () ->
                svc.completeUpload(session.sessionId(), List.of(1),
                        List.of(p.checksum()), wrongObjectChecksum));
        assertEquals(ErrorCode.OBJECT_CHECKSUM_MISMATCH, mismatch.getCode());
        System.out.println("[basic] rejected object checksum mismatch: " + mismatch.getMessage());
    }

    @Test
    void pathTraversalKeyRejected() {
        ObjectStorageService svc = service();
        assertThrows(StorageException.class, () -> svc.initiateUpload("../escape"));
        assertThrows(StorageException.class, () -> svc.initiateUpload("a\\b"));
    }

    @Test
    void singleShotPutAndGet() {
        ObjectStorageService svc = service();
        byte[] content = utf8("{\"a\":1}");
        CompleteResult result = svc.putObject("json/obj", new ByteArrayInputStream(content), sha256Hex(content));
        assertEquals(1, result.manifest().version());
        assertArrayEquals(content, svc.readCurrentBytes("json/obj"));

        VersionManifest v = svc.getVersion("json/obj", 1);
        assertEquals(sha256Hex(content), v.checksum());
    }
}
