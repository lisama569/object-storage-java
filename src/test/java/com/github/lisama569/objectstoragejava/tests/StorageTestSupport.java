package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.config.StorageProperties;
import com.github.lisama569.objectstoragejava.dto.ManifestEntry;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.service.ChecksumService;
import com.github.lisama569.objectstoragejava.service.LockManager;
import com.github.lisama569.objectstoragejava.service.ObjectStorageService;
import com.github.lisama569.objectstoragejava.service.ReadGate;
import com.github.lisama569.objectstoragejava.service.StorageLayout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers: wires a real service stack against a JUnit-managed temp directory. */
final class StorageTestSupport {

    private StorageTestSupport() {
    }

    record Stack(Path root, ObjectStorageService service, ChecksumService checksums, ReadGate readGate) {
    }

    static Stack newStack(Path root) {
        StorageProperties properties = new StorageProperties(root.toString());
        StorageLayout layout = new StorageLayout(properties);
        layout.initialize();
        ChecksumService checksums = new ChecksumService();
        ReadGate readGate = new ReadGate();
        ObjectStorageService service = new ObjectStorageService(layout, checksums, new LockManager(), readGate);
        return new Stack(root, service, checksums, readGate);
    }

    static String sha256(ChecksumService checksums, byte[] data) {
        return checksums.sha256(new ByteArrayInputStream(data));
    }

    /** Upload all parts and complete; returns the published (or reused) version. */
    static StoredObject multipartUpload(ObjectStorageService service, ChecksumService checksums,
                                        String objectName, List<byte[]> parts, String uploadId) {
        List<ManifestEntry> entries = new ArrayList<>();
        List<String> partChecksums = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            byte[] part = parts.get(i);
            String checksum = sha256(checksums, part);
            service.uploadPart(objectName, uploadId, i + 1, checksum, new ByteArrayInputStream(part));
            entries.add(new ManifestEntry(i + 1, checksum));
            partChecksums.add(checksum);
        }
        String objectChecksum = checksums.combinePartChecksums(partChecksums);
        return service.completeUpload(objectName, uploadId, new ManifestRequest(objectChecksum, entries));
    }

    /**
     * Upload and complete with a freshly initialised session, additionally claiming the
     * chunking-independent SHA-256 over the assembled bytes as {@code contentChecksum}.
     */
    static StoredObject multipartUploadWithContentChecksum(ObjectStorageService service,
                                                           ChecksumService checksums,
                                                           String objectName, List<byte[]> parts) {
        UploadInitResponse init = service.initUpload(objectName);
        return multipartUploadWithContentChecksum(service, checksums, objectName, parts, init.uploadId());
    }

    /**
     * Upload and complete on a given session, claiming the chunking-independent SHA-256 over
     * the assembled bytes as {@code contentChecksum}.
     */
    static StoredObject multipartUploadWithContentChecksum(ObjectStorageService service,
                                                           ChecksumService checksums,
                                                           String objectName, List<byte[]> parts,
                                                           String uploadId) {
        List<ManifestEntry> entries = new ArrayList<>();
        List<String> partChecksums = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            byte[] part = parts.get(i);
            String checksum = sha256(checksums, part);
            service.uploadPart(objectName, uploadId, i + 1, checksum, new ByteArrayInputStream(part));
            entries.add(new ManifestEntry(i + 1, checksum));
            partChecksums.add(checksum);
        }
        String chained = checksums.combinePartChecksums(partChecksums);
        String contentChecksum = sha256(checksums, concat(parts));
        return service.completeUpload(objectName, uploadId,
                new ManifestRequest(chained, entries, contentChecksum));
    }

    static StoredObject multipartUpload(ObjectStorageService service, ChecksumService checksums,
                                        String objectName, List<byte[]> parts) {
        UploadInitResponse init = service.initUpload(objectName);
        return multipartUpload(service, checksums, objectName, parts, init.uploadId());
    }

    static byte[] concat(List<byte[]> parts) {
        int total = parts.stream().mapToInt(p -> p.length).sum();
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, offset, p.length);
            offset += p.length;
        }
        return all;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Drain a storage stream AND close it. {@link InputStream#readAllBytes()} intentionally does
     * not close the stream, and an unclosed version stream would leak the GC-excluding read
     * lease, so tests must always read through this helper.
     */
    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }
}
