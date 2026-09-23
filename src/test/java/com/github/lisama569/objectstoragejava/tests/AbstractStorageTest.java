package com.github.lisama569.objectstoragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageServiceImpl;
import com.github.lisama569.objectstoragejava.storage.StorageProperties;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 存储服务测试基类：每个测试使用独立临时目录构造真实服务（不启动 Spring），
 * 并提供分块上传、校验和等辅助方法。
 */
abstract class AbstractStorageTest {

    /** 一个分块上传的结果记录，供断言与清单使用。 */
    record UploadedPart(int number, String checksum) {
    }

    ObjectStorageService newService(String rootDir) {
        StorageProperties props = new StorageProperties();
        props.setRoot(rootDir);
        ObjectStorageServiceImpl service = new ObjectStorageServiceImpl(props, new ObjectMapper());
        service.recoverForTests();
        return service;
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 把 content 按固定大小切块，完成一次分片上传。 */
    CompleteResult multipartUpload(ObjectStorageService service, String key,
                                   byte[] content, int chunkSize,
                                   boolean sendExpectedChunkChecksum) {
        UploadSessionMeta session = service.initiateUpload(key);
        List<UploadedPart> parts = new ArrayList<>();
        int number = 1;
        for (int offset = 0; offset < content.length; offset += chunkSize, number++) {
            int end = Math.min(offset + chunkSize, content.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(content, offset, chunk, 0, chunk.length);
            String expected = sendExpectedChunkChecksum ? sha256Hex(chunk) : null;
            StoredChunk stored = service.uploadPart(session.sessionId(), number,
                    new ByteArrayInputStream(chunk), expected);
            parts.add(new UploadedPart(number, stored.checksum()));
        }
        List<Integer> numbers = parts.stream().map(UploadedPart::number).toList();
        List<String> checksums = parts.stream().map(UploadedPart::checksum).toList();
        return service.completeUpload(session.sessionId(), numbers, checksums, sha256Hex(content));
    }

    CompleteResult multipartUpload(ObjectStorageService service, String key,
                                   byte[] content, int chunkSize) {
        return multipartUpload(service, key, content, chunkSize, true);
    }
}
