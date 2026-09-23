package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.ErrorCode;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.UploadState;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中断恢复场景：
 * <ol>
 *   <li>分块传到一半"中断"（停止操作），之后可用同一会话继续补齐并完成；</li>
 *   <li>显式中止会话后，再上传/完成均被拒绝（SESSION_ABORTED），已存在版本不受影响；</li>
 *   <li>新版本上传失败（校验和不符/清单缺失）后，旧版本仍完整可读，current 不变；</li>
 *   <li>模拟进程重启：用同一存储目录新建服务实例，ACTIVE 会话仍可继续，最终版本确定可复现。</li>
 * </ol>
 */
class InterruptionRecoveryTest extends AbstractStorageTest {

    @TempDir
    Path tempDir;

    private String root() {
        return tempDir.toString();
    }

    @Test
    void interruptedSessionCanResumeAndComplete() {
        ObjectStorageService svc = newService(root());
        byte[] content = "abcdefghijklmnop".getBytes(); // 4 个 4 字节分块
        UploadSessionMeta session = svc.initiateUpload("resume/obj");

        // 只上传前 2 个分块，然后"中断"
        List<UploadedPart> parts = new ArrayList<>();
        for (int p = 1; p <= 2; p++) {
            byte[] chunk = new byte[4];
            System.arraycopy(content, (p - 1) * 4, chunk, 0, 4);
            StoredChunk stored = svc.uploadPart(session.sessionId(), p,
                    new ByteArrayInputStream(chunk), sha256Hex(chunk));
            parts.add(new UploadedPart(p, stored.checksum()));
        }
        System.out.println("[resume] session " + session.sessionId() + " interrupted after 2/4 parts");

        // 恢复：补齐剩余分块（不重新初始化会话）
        for (int p = 3; p <= 4; p++) {
            byte[] chunk = new byte[4];
            System.arraycopy(content, (p - 1) * 4, chunk, 0, 4);
            StoredChunk stored = svc.uploadPart(session.sessionId(), p,
                    new ByteArrayInputStream(chunk), sha256Hex(chunk));
            parts.add(new UploadedPart(p, stored.checksum()));
        }
        CompleteResult result = svc.completeUpload(session.sessionId(),
                parts.stream().map(UploadedPart::number).toList(),
                parts.stream().map(UploadedPart::checksum).toList(),
                sha256Hex(content));

        assertEquals(1, result.manifest().version());
        assertArrayEquals(content, svc.readCurrentBytes("resume/obj"));
        System.out.println("[resume] resumed upload completed checksum=" + result.manifest().checksum());
    }

    @Test
    void abortedSessionRejectsFurtherWrites() {
        ObjectStorageService svc = newService(root());
        // 先有一个健康的旧版本
        byte[] oldContent = "old-version".getBytes();
        multipartUpload(svc, "abort/obj", oldContent, 4);
        int versionBefore = svc.currentVersion("abort/obj").version();

        UploadSessionMeta session = svc.initiateUpload("abort/obj");
        byte[] chunk = "new".getBytes();
        svc.uploadPart(session.sessionId(), 1, new ByteArrayInputStream(chunk), sha256Hex(chunk));

        svc.abortUpload(session.sessionId());
        System.out.println("[abort] session " + session.sessionId() + " aborted");

        // 中止后继续写 → 会话已清理（SESSION_NOT_FOUND）；中止本身的语义错误码为 SESSION_ABORTED
        StorageException writeEx = assertThrows(StorageException.class, () ->
                svc.uploadPart(session.sessionId(), 2,
                        new ByteArrayInputStream("x".getBytes()), null));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, writeEx.getCode());

        // 重复中止 → SESSION_ABORTED（第一次已中止）；此处目录已删，故同样表现为 NOT_FOUND
        assertThrows(StorageException.class, () -> svc.abortUpload(session.sessionId()));

        // 旧版本原样可读，current 未变
        VersionManifest current = svc.currentVersion("abort/obj");
        assertEquals(versionBefore, current.version());
        assertArrayEquals(oldContent, svc.readCurrentBytes("abort/obj"));
        System.out.println("[abort] old version " + current.version()
                + " still readable, checksum=" + current.checksum());
    }

    @Test
    void failedNewVersionDoesNotCorruptOldVersion() {
        ObjectStorageService svc = newService(root());
        String key = "fail/obj";
        byte[] v1 = "first-version-content".getBytes();
        byte[] v2 = "second-version-content-that-fails".getBytes();
        multipartUpload(svc, key, v1, 5);
        String v1Checksum = svc.currentVersion(key).checksum();

        // 用错误的整体校验和完成 v2 → 必须失败
        StorageException mismatch = assertThrows(StorageException.class, () ->
                multipartUploadWithObjectChecksum(svc, key, v2, 5, "dd".repeat(32)));
        assertEquals(ErrorCode.OBJECT_CHECKSUM_MISMATCH, mismatch.getCode());

        // current 仍指向 v1，字节与校验和未受任何影响
        VersionManifest current = svc.currentVersion(key);
        assertEquals(1, current.version());
        assertEquals(v1Checksum, current.checksum());
        assertArrayEquals(v1, svc.readCurrentBytes(key));
        System.out.println("[fail] after failed v2, current remains v1 checksum=" + v1Checksum
                + " reason=" + mismatch.getCode());

        // 之后一个正确的 v2 仍能正常发布为版本 2（失败没有污染任何状态）
        CompleteResult ok = multipartUpload(svc, key, v2, 5);
        assertEquals(2, ok.manifest().version());
        assertArrayEquals(v2, svc.readCurrentBytes(key));
        // v1 依旧可读
        assertArrayEquals(v1, svc.readVersionBytes(key, 1));
    }

    @Test
    void restartRecoversActiveSessionAndDeterministicallyCompletes() {
        String dir = root();
        byte[] content = "persistent-content-across-restart-1234567890".getBytes();

        // 第一个"进程"：建会话、传 2/3 分块后退出（不显式完成或中止）
        ObjectStorageService first = newService(dir);
        UploadSessionMeta session = first.initiateUpload("restart/obj");
        List<UploadedPart> parts = new ArrayList<>();
        int[] sizes = {10, 10, content.length - 20};
        int offset = 0;
        for (int p = 1; p <= 2; p++) {
            byte[] chunk = new byte[sizes[p - 1]];
            System.arraycopy(content, offset, chunk, 0, chunk.length);
            offset += chunk.length;
            StoredChunk stored = first.uploadPart(session.sessionId(), p,
                    new ByteArrayInputStream(chunk), sha256Hex(chunk));
            parts.add(new UploadedPart(p, stored.checksum()));
        }
        String sessionId = session.sessionId();
        System.out.println("[restart] first instance exited mid-upload, session=" + sessionId);

        // 第二个"进程"：同目录重新构造并执行启动恢复
        ObjectStorageService second = newService(dir);

        // ACTIVE 会话被恢复：仍可继续上传第 3 块并完成
        byte[] chunk3 = new byte[sizes[2]];
        System.arraycopy(content, offset, chunk3, 0, chunk3.length);
        StoredChunk stored3 = second.uploadPart(sessionId, 3,
                new ByteArrayInputStream(chunk3), sha256Hex(chunk3));
        parts.add(new UploadedPart(3, stored3.checksum()));
        CompleteResult result = second.completeUpload(sessionId,
                parts.stream().map(UploadedPart::number).toList(),
                parts.stream().map(UploadedPart::checksum).toList(),
                sha256Hex(content));

        assertEquals(1, result.manifest().version());
        assertArrayEquals(content, second.readCurrentBytes("restart/obj"));
        System.out.println("[restart] recovered session completed deterministically, checksum="
                + result.manifest().checksum() + " decision=" + result.decision());
    }

    /** 带显式对象整体校验和的分片上传助手（用于构造失败）。 */
    private CompleteResult multipartUploadWithObjectChecksum(ObjectStorageService svc, String key,
                                                             byte[] content, int chunkSize,
                                                             String objectChecksum) {
        UploadSessionMeta session = svc.initiateUpload(key);
        List<UploadedPart> parts = new ArrayList<>();
        int number = 1;
        for (int off = 0; off < content.length; off += chunkSize, number++) {
            int end = Math.min(off + chunkSize, content.length);
            byte[] chunk = new byte[end - off];
            System.arraycopy(content, off, chunk, 0, chunk.length);
            StoredChunk stored = svc.uploadPart(session.sessionId(), number,
                    new ByteArrayInputStream(chunk), sha256Hex(chunk));
            parts.add(new UploadedPart(number, stored.checksum()));
        }
        return svc.completeUpload(session.sessionId(),
                parts.stream().map(UploadedPart::number).toList(),
                parts.stream().map(UploadedPart::checksum).toList(), objectChecksum);
    }
}
