package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 去重规则与版本管理：
 * <ul>
 *   <li>内容寻址：相同分块字节在 blobs 目录只有一份物理文件；</li>
 *   <li>相同整体内容重复上传：current 版本不增加（对象级幂等）；</li>
 *   <li>不同内容产生单调递增新版本，旧版本清单/字节永久可读。</li>
 * </ul>
 */
class DedupVersionTest extends AbstractStorageTest {

    @TempDir
    Path tempDir;

    private long countBlobs(Path root) throws IOException {
        Path blobs = root.resolve("blobs");
        if (!Files.exists(blobs)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(blobs)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void identicalContentReuploadDoesNotCreateVersion() {
        ObjectStorageService svc = newService(tempDir.toString());
        byte[] content = utf8("repeatable-object-content");
        CompleteResult first = multipartUpload(svc, "obj/dup", content, 6);
        assertEquals(1, first.manifest().version());
        assertFalse(first.deduplicated());

        CompleteResult second = multipartUpload(svc, "obj/dup", content, 6);
        CompleteResult third = multipartUpload(svc, "obj/dup", content, 6);
        assertEquals(1, second.manifest().version());
        assertEquals(1, third.manifest().version());
        assertTrue(second.deduplicated());
        assertTrue(third.deduplicated());
        System.out.println("[dedup] 1st decision=" + first.decision());
        System.out.println("[dedup] 2nd decision=" + second.decision());
        System.out.println("[dedup] final current version=" + svc.currentVersion("obj/dup").version());
        assertEquals(1, svc.currentVersion("obj/dup").version());
    }

    @Test
    void changedContentCreatesMonotonicVersionsAndOldVersionsStayReadable() {
        ObjectStorageService svc = newService(tempDir.toString());
        String key = "docs/versioned.txt";
        byte[] v1 = utf8("version-one-content");
        byte[] v2 = utf8("version-two-content-different") ;
        byte[] v3 = utf8("version-three-content-yet-again");

        CompleteResult r1 = multipartUpload(svc, key, v1, 5);
        CompleteResult r2 = multipartUpload(svc, key, v2, 5);
        CompleteResult r3 = multipartUpload(svc, key, v3, 5);

        assertEquals(1, r1.manifest().version());
        assertEquals(2, r2.manifest().version());
        assertEquals(3, r3.manifest().version());
        System.out.println("[version] v1 checksum=" + r1.manifest().checksum());
        System.out.println("[version] v2 checksum=" + r2.manifest().checksum());
        System.out.println("[version] v3 checksum=" + r3.manifest().checksum());
        System.out.println("[version] v3 decision=" + r3.decision());

        // 当前是 v3，且每个旧版本仍可按版本号读取并校验通过
        assertEquals(3, svc.currentVersion(key).version());
        assertArrayEquals(v1, svc.readVersionBytes(key, 1));
        assertArrayEquals(v2, svc.readVersionBytes(key, 2));
        assertArrayEquals(v3, svc.readVersionBytes(key, 3));
        assertArrayEquals(v3, svc.readCurrentBytes(key));
    }

    @Test
    void identicalChunksAcrossObjectsShareSingleBlob() throws IOException {
        ObjectStorageService svc = newService(tempDir.toString());
        byte[] sharedChunk = utf8("SHARED-CHUNK-BYTES-12345");
        byte[] tailA = utf8("tail-A");
        byte[] tailB = utf8("tail-BBB");

        // 对象 X 与对象 Y 都包含完全相同的第一个分块
        UploadWithSession x = uploadTwoChunks(svc, "obj/x", sharedChunk, tailA);
        UploadWithSession y = uploadTwoChunks(svc, "obj/y", sharedChunk, tailB);

        long blobsBefore = countBlobs(tempDir);
        // sharedChunk + tailA + tailB = 3 个唯一 blob
        System.out.println("[dedup] physical blobs after 2 objects sharing a chunk = " + blobsBefore);
        assertEquals(3, blobsBefore, "identical chunks must be stored once");

        // 再次完整重复上传两个对象（保持相同分块边界），blob 数不增加
        multipartUpload(svc, "obj/x", concat(sharedChunk, tailA), sharedChunk.length);
        multipartUpload(svc, "obj/y", concat(sharedChunk, tailB), sharedChunk.length);
        long blobsAfter = countBlobs(tempDir);
        System.out.println("[dedup] physical blobs after re-uploads = " + blobsAfter);
        assertEquals(blobsBefore, blobsAfter, "re-uploads must not create redundant blobs");
    }

    @Test
    void reUploadWithDifferentChunkingOfSameContentIsIdempotent() {
        // 同样的整体内容，用不同的分块边界上传：对象级仍判为相同内容 → 不产生新版本
        ObjectStorageService svc = newService(tempDir.toString());
        String key = "obj/rechunk";
        byte[] content = utf8("the-quick-brown-fox-jumps-over-lazy-dog-0123456789");
        CompleteResult r1 = multipartUpload(svc, key, content, 7);
        CompleteResult r2 = multipartUpload(svc, key, content, 13);
        CompleteResult r3 = multipartUpload(svc, key, content, content.length);

        assertEquals(1, r1.manifest().version());
        assertEquals(1, r2.manifest().version());
        assertEquals(1, r3.manifest().version());
        assertTrue(r3.deduplicated());
        assertEquals(r1.manifest().checksum(), r3.manifest().checksum());
        System.out.println("[dedup] different chunking, same checksum=" + r3.manifest().checksum()
                + " decision=" + r3.decision());
    }

    private record UploadWithSession() {
    }

    private UploadWithSession uploadTwoChunks(ObjectStorageService svc, String key,
                                              byte[] chunk1, byte[] chunk2) {
        multipartUpload(svc, key, concat(chunk1, chunk2), chunk1.length);
        return new UploadWithSession();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
