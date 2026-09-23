package com.github.lisama569.objectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.ErrorCode;
import com.github.lisama569.objectstoragejava.model.ManifestPart;
import com.github.lisama569.objectstoragejava.model.ObjectSnapshot;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.UploadState;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 本地对象存储服务实现。
 *
 * <p><b>并发语义</b>：每个对象键持有一把锁（{@link #keyLocks}），
 * 版本发布（校验 → 分配版本号 → 原子切换 current）在对象锁内完成。
 * 因此同一对象的多个并发写入者按到达顺序串行发布，版本号单调递增、不会回退；
 * 读取方不加锁，仅依靠"清单不可变 + current 指针原子切换"看到某个完整版本。
 *
 * <p><b>失败语义</b>：发布前的任何校验失败都只影响当前会话；current 指针与旧清单
 * 完全不被触碰，旧版本保持可读。发布过程中断留下的残留由启动恢复清理。
 */
public class ObjectStorageServiceImpl implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageServiceImpl.class);

    private final StorageProperties properties;
    private final ObjectMapper mapper;
    private final StorageLayout layout;
    private final ContentAddressedStore blobStore;
    private final VersionStore versionStore;
    private final SessionStore sessionStore;

    /**
     * 分段锁：按 key 的哈希映射到固定数量、永不移除的锁对象上。
     *
     * <p><b>不能</b>在用完后把锁从 map 移除：若线程 B 正阻塞等待线程 A 持有的锁对象，
     * A 退出时 remove 会让新到达的线程 C computeIfAbsent 出一把全新锁，B、C 即并发进入
     * 临界区，版本号会基于过期 current 分配而回退。固定分段锁彻底避免该问题。
     */
    private static final int KEY_LOCK_STRIPES = 256;
    private final Object[] keyLockStripes = createStripes(KEY_LOCK_STRIPES);

    /** 会话级分段锁：串行化同一会话的分块写入/完成/中止。 */
    private static final int SESSION_LOCK_STRIPES = 256;
    private final Object[] sessionLockStripes = createStripes(SESSION_LOCK_STRIPES);

    private static Object[] createStripes(int count) {
        Object[] stripes = new Object[count];
        for (int i = 0; i < count; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private Object keyLock(String objectKey) {
        return keyLockStripes[Math.floorMod(objectKey.hashCode(), KEY_LOCK_STRIPES)];
    }

    private Object sessionLock(String sessionId) {
        return sessionLockStripes[Math.floorMod(sessionId.hashCode(), SESSION_LOCK_STRIPES)];
    }

    public ObjectStorageServiceImpl(StorageProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.layout = new StorageLayout(properties.getRoot());
        this.blobStore = new ContentAddressedStore(layout);
        this.versionStore = new VersionStore(layout, mapper);
        this.sessionStore = new SessionStore(layout, mapper);
    }

    @PostConstruct
    void recover() {
        doRecover();
    }

    /** 非 Spring 环境（如单元测试）手动触发启动恢复。 */
    public void recoverForTests() {
        doRecover();
    }

    private void doRecover() {
        try {
            Files.createDirectories(layout.blobsDir());
            Files.createDirectories(layout.objectsDir());
            Files.createDirectories(layout.sessionsDir());
            Files.createDirectories(layout.tmpDir());
            clearTmpDir();
            int strayManifests = versionStore.recoverStrayManifests();
            List<UploadSessionMeta> activeSessions = sessionStore.recoverSessions();
            log.info("object storage recovered at root={}: strayManifestsRemoved={}, resumableActiveSessions={}",
                    layout.root(), strayManifests, activeSessions.size());
            for (UploadSessionMeta s : activeSessions) {
                log.info("resumable session {} for object '{}' created at {}",
                        s.sessionId(), s.objectKey(), s.createdAt());
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(ErrorCode.IO_ERROR, "storage startup recovery failed", e);
        }
    }

    private void clearTmpDir() {
        Path tmp = layout.tmpDir();
        if (!Files.isDirectory(tmp)) {
            return;
        }
        try (Stream<Path> entries = Files.list(tmp)) {
            entries.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.warn("failed to clear tmp dir on startup: {}", e.getMessage());
        }
    }

    private void ensureActive(UploadSessionMeta session) {
        if (session.state() == UploadState.ABORTED) {
            throw new StorageException(ErrorCode.SESSION_ABORTED,
                    "session has been aborted: " + session.sessionId());
        }
        if (session.state() == UploadState.COMPLETED) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND,
                    "session already completed and finalized: " + session.sessionId());
        }
    }

    @Override
    public UploadSessionMeta initiateUpload(String objectKey) {
        String key = layout.validateKey(objectKey);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        UploadSessionMeta meta = new UploadSessionMeta(sessionId, key, System.currentTimeMillis(),
                UploadState.ACTIVE);
        sessionStore.createSession(meta);
        log.info("initiated upload session {} for object '{}'", sessionId, key);
        return meta;
    }

    @Override
    public StoredChunk uploadPart(String sessionId, int partNumber, InputStream content,
                                  String expectedChecksum) {
        if (partNumber < 1 || partNumber > 100_000) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "part number out of range: " + partNumber);
        }
        if (expectedChecksum != null && !Checksums.isSha256Hex(expectedChecksum)) {
            throw new StorageException(ErrorCode.BAD_REQUEST,
                    "X-Content-SHA256 must be 64 hex characters");
        }
        UploadSessionMeta session = sessionStore.loadSession(sessionId);
        ensureActive(session);

        Object lock = sessionLock(sessionId);
        synchronized (lock) {
            ensureActive(sessionStore.loadSession(sessionId));
            ContentAddressedStore.StoredBlob blob = blobStore.store(content, expectedChecksum);
            SessionStore.PartRecord existing = sessionStore.loadParts(sessionId).get(partNumber);
            String note = "";
            if (existing != null) {
                note = existing.checksum().equalsIgnoreCase(blob.checksum())
                        ? " (identical re-upload, reused)"
                        : " (part replaced with new content)";
            }
            sessionStore.recordPart(sessionId,
                    new SessionStore.PartRecord(partNumber, blob.checksum(), blob.size()));
            log.info("session {} part {} stored: checksum={}, size={}{}",
                    sessionId, partNumber, blob.checksum(), blob.size(), note);
            return new StoredChunk(blob.checksum(), blob.size());
        }
    }

    @Override
    public CompleteResult completeUpload(String sessionId, List<Integer> orderedPartNumbers,
                                         List<String> orderedChecksums,
                                         String expectedObjectChecksum) {
        if (expectedObjectChecksum != null && !Checksums.isSha256Hex(expectedObjectChecksum)) {
            throw new StorageException(ErrorCode.BAD_REQUEST,
                    "expected object checksum must be 64 hex characters");
        }
        // 1. 清单存在性校验：缺失或为空 → MANIFEST_MISSING
        if (orderedPartNumbers == null || orderedPartNumbers.isEmpty()
                || orderedChecksums == null || orderedChecksums.isEmpty()) {
            throw new StorageException(ErrorCode.MANIFEST_MISSING,
                    "manifest is missing or contains no parts for session " + sessionId);
        }
        if (orderedPartNumbers.size() != orderedChecksums.size()) {
            throw new StorageException(ErrorCode.MANIFEST_MISSING,
                    "manifest part numbers and checksums have different lengths");
        }

        UploadSessionMeta session = sessionStore.loadSession(sessionId);
        ensureActive(session);
        String objectKey = session.objectKey();

        synchronized (sessionLock(sessionId)) {
            ensureActive(sessionStore.loadSession(sessionId));
            Map<Integer, SessionStore.PartRecord> recorded = sessionStore.loadParts(sessionId);

                // 2. 清单完整性校验：序号必须从 1 开始连续，每个引用都必须真实存在且校验和一致
                List<ManifestPart> manifestParts = validateManifest(
                        orderedPartNumbers, orderedChecksums, recorded);

                List<String> orderedBlobChecksums = manifestParts.stream()
                        .map(ManifestPart::checksum).toList();

                // 3. 服务端按序拼接所有 blob，独立计算对象整体校验和（不盲信客户端）
                ContentAddressedStore.CombinedHash combined = blobStore.combine(orderedBlobChecksums);
                log.info("session {} assembled object '{}' from {} parts: serverChecksum={}, size={}",
                        sessionId, objectKey, manifestParts.size(), combined.checksum(), combined.size());

                if (expectedObjectChecksum != null
                        && !expectedObjectChecksum.equalsIgnoreCase(combined.checksum())) {
                    throw new StorageException(ErrorCode.OBJECT_CHECKSUM_MISMATCH,
                            "object checksum mismatch: expected=" + expectedObjectChecksum
                                    + ", actual=" + combined.checksum());
                }

                // 4. 对象锁内：去重判定 + 分配单调版本号 + 原子发布
                synchronized (keyLock(objectKey)) {
                    VersionManifest current = versionStore.readCurrent(objectKey);
                    long now = System.currentTimeMillis();

                    if (current != null
                            && current.checksum().equalsIgnoreCase(combined.checksum())
                            && current.size() == combined.size()) {
                        // 内容与当前版本逐字节相同：去重，不分配新版本号
                        String decision = "content identical to current version " + current.version()
                                + " (checksum=" + combined.checksum() + ", size=" + combined.size()
                                + "); no new version created";
                        log.info("object '{}' upload completed (deduplicated): {}", objectKey, decision);
                        sessionStore.updateState(sessionId, UploadState.COMPLETED);
                        sessionStore.deleteSession(sessionId);
                        return new CompleteResult(current, true, decision);
                    }

                    int nextVersion = (current == null ? 0 : current.version()) + 1;
                    VersionManifest manifest = new VersionManifest(
                            nextVersion, objectKey, combined.checksum(), combined.size(),
                            Collections.unmodifiableList(new ArrayList<>(manifestParts)),
                            session.createdAt(), now);
                    versionStore.publish(objectKey, nextVersion, manifest);

                    String decision = "published new version " + nextVersion
                            + (current == null ? " (object did not exist; created from scratch)"
                            : " (previous current version was " + current.version() + ")")
                            + "; checksum=" + combined.checksum() + ", size=" + combined.size();
                    log.info("object '{}' upload completed: {}", objectKey, decision);

                    sessionStore.updateState(sessionId, UploadState.COMPLETED);
                    sessionStore.deleteSession(sessionId);
                    return new CompleteResult(manifest, false, decision);
                }
        }
    }

    /**
     * 校验清单：序号 1..N 连续；每个分块在会话中真实上传；清单引用的校验和与存储一致。
     */
    private List<ManifestPart> validateManifest(List<Integer> orderedPartNumbers,
                                                List<String> orderedChecksums,
                                                Map<Integer, SessionStore.PartRecord> recorded) {
        List<ManifestPart> parts = new ArrayList<>(orderedPartNumbers.size());
        for (int i = 0; i < orderedPartNumbers.size(); i++) {
            int expectedNumber = i + 1;
            Integer number = orderedPartNumbers.get(i);
            String checksum = orderedChecksums.get(i);
            if (number == null || number != expectedNumber) {
                throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                        "manifest parts must be consecutive starting at 1; expected part "
                                + expectedNumber + " but got " + number);
            }
            if (!Checksums.isSha256Hex(checksum)) {
                throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                        "manifest entry for part " + expectedNumber + " has invalid checksum");
            }
            SessionStore.PartRecord record = recorded.get(number);
            if (record == null) {
                throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                        "manifest references part " + number + " which was never uploaded");
            }
            if (!record.checksum().equalsIgnoreCase(checksum)) {
                throw new StorageException(ErrorCode.CHUNK_CHECKSUM_MISMATCH,
                        "manifest checksum for part " + number + " is " + checksum
                                + " but stored part has " + record.checksum());
            }
            if (!blobStore.exists(checksum)) {
                throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                        "manifest references chunk content that is absent from store: " + checksum);
            }
            parts.add(new ManifestPart(number, record.checksum(), record.size()));
        }
        return parts;
    }

    @Override
    public void abortUpload(String sessionId) {
        UploadSessionMeta session = sessionStore.loadSession(sessionId);
        synchronized (sessionLock(sessionId)) {
            if (session.state() == UploadState.ABORTED) {
                throw new StorageException(ErrorCode.SESSION_ABORTED,
                        "session already aborted: " + sessionId);
            }
            sessionStore.updateState(sessionId, UploadState.ABORTED);
            // 删除会话及其分块引用；共享 blob 与已存在版本一律不动
            sessionStore.deleteSession(sessionId);
            log.info("session {} aborted; existing versions of '{}' are untouched",
                    sessionId, session.objectKey());
        }
    }

    @Override
    public CompleteResult putObject(String objectKey, InputStream content, String expectedObjectChecksum) {
        String key = layout.validateKey(objectKey);
        UploadSessionMeta session = initiateUpload(key);
        try {
            StoredChunk chunk = uploadPart(session.sessionId(), 1, content, null);
            return completeUpload(session.sessionId(),
                    List.of(1), List.of(chunk.checksum()), expectedObjectChecksum);
        } catch (StorageException e) {
            // 单次上传失败：清理会话，绝不留下半成品版本
            safeAbort(session.sessionId());
            throw e;
        } catch (RuntimeException e) {
            safeAbort(session.sessionId());
            throw new StorageException(ErrorCode.IO_ERROR, "single-shot upload failed", e);
        }
    }

    private void safeAbort(String sessionId) {
        try {
            abortUpload(sessionId);
        } catch (Exception ignored) {
            // 补偿性清理失败不掩盖原始错误
        }
    }

    @Override
    public VersionManifest currentVersion(String objectKey) {
        String key = layout.validateKey(objectKey);
        VersionManifest manifest = versionStore.readCurrent(key);
        if (manifest == null) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                    "object '" + key + "' does not exist");
        }
        return manifest;
    }

    @Override
    public VersionManifest getVersion(String objectKey, int version) {
        String key = layout.validateKey(objectKey);
        if (version < 1) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "version must be >= 1");
        }
        int current = versionStore.currentVersionNumber(key);
        if (version > current) {
            throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                    "version " + version + " not found (current=" + current + ")");
        }
        return versionStore.readVersion(key, version);
    }

    @Override
    public ObjectSnapshot readCurrentSnapshot(String objectKey) {
        String key = layout.validateKey(objectKey);
        // 指针只读一次：清单与其字节必然同属一个版本
        VersionManifest manifest = versionStore.readCurrent(key);
        if (manifest == null) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                    "object '" + key + "' does not exist");
        }
        return new ObjectSnapshot(manifest, readManifestBytes(manifest));
    }

    @Override
    public byte[] readCurrentBytes(String objectKey) {
        return readCurrentSnapshot(objectKey).bytes();
    }

    @Override
    public byte[] readVersionBytes(String objectKey, int version) {
        return readManifestBytes(getVersion(objectKey, version));
    }

    /**
     * 按版本清单顺序拼装全部 blob，读完后校验大小与整体 SHA-256；
     * 与清单不符即抛出 {@link ErrorCode#OBJECT_CHECKSUM_MISMATCH}，绝不返回损坏字节。
     */
    private byte[] readManifestBytes(VersionManifest manifest) {
        List<String> checksums = manifest.parts().stream().map(ManifestPart::checksum).toList();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        java.security.MessageDigest digest = Checksums.sha256();
        try (java.security.DigestOutputStream out =
                     new java.security.DigestOutputStream(collected, digest)) {
            long size = blobStore.copyCombined(checksums, out);
            if (size != manifest.size()) {
                throw new StorageException(ErrorCode.OBJECT_CHECKSUM_MISMATCH,
                        "stored object size mismatch for version " + manifest.version()
                                + ": manifest=" + manifest.size() + ", actual=" + size);
            }
        } catch (java.io.IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "failed to read object", e);
        }
        String actual = java.util.HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(manifest.checksum())) {
            // 存储内容与清单不符：显式拒绝，绝不静默返回损坏数据
            throw new StorageException(ErrorCode.OBJECT_CHECKSUM_MISMATCH,
                    "stored object checksum mismatch for version " + manifest.version()
                            + ": manifest=" + manifest.checksum() + ", actual=" + actual);
        }
        return collected.toByteArray();
    }
}
