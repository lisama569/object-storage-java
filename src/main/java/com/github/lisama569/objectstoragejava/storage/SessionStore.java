package com.github.lisama569.objectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.ErrorCode;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.UploadState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 分片上传会话存储。
 *
 * <p>会话元数据（session.json）与每个已上传分块的引用（parts/N.json）都持久化到磁盘。
 * 因此上传中途进程退出、重启后仍可扫描恢复 ACTIVE 会话继续上传或中止；
 * 已完成/已中止的会话目录会被删除，不占空间。
 */
final class SessionStore {

    /** 一个已上传分块的持久化引用。 */
    record PartRecord(int partNumber, String checksum, long size) {
    }

    private final StorageLayout layout;
    private final ObjectMapper mapper;

    SessionStore(StorageLayout layout, ObjectMapper mapper) {
        this.layout = layout;
        this.mapper = mapper;
    }

    void createSession(UploadSessionMeta meta) {
        try {
            Files.createDirectories(layout.sessionPartsDir(meta.sessionId()));
            writeJson(layout.sessionMetaFile(meta.sessionId()), meta);
            FileDurability.fsyncDir(layout.sessionDir(meta.sessionId()));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot create session", e);
        }
    }

    UploadSessionMeta loadSession(String sessionId) {
        Path file = layout.sessionMetaFile(sessionId);
        if (!Files.exists(file)) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "session not found: " + sessionId);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return mapper.readValue(in, UploadSessionMeta.class);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot read session " + sessionId, e);
        }
    }

    void recordPart(String sessionId, PartRecord record) {
        try {
            Files.createDirectories(layout.sessionPartsDir(sessionId));
            writeJson(layout.sessionPartFile(sessionId, record.partNumber()), record);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot record part", e);
        }
    }

    /** 某会话全部已上传分块，按分块序号排序。 */
    Map<Integer, PartRecord> loadParts(String sessionId) {
        Path partsDir = layout.sessionPartsDir(sessionId);
        if (!Files.isDirectory(partsDir)) {
            return new HashMap<>();
        }
        Map<Integer, PartRecord> result = new HashMap<>();
        try (Stream<Path> files = Files.list(partsDir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    PartRecord record = mapper.readValue(in, PartRecord.class);
                    result.put(record.partNumber(), record);
                }
            }
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot list parts of session " + sessionId, e);
        }
        return result;
    }

    void updateState(String sessionId, UploadState state) {
        UploadSessionMeta existing = loadSession(sessionId);
        UploadSessionMeta updated = new UploadSessionMeta(
                existing.sessionId(), existing.objectKey(), existing.createdAt(), state);
        writeJson(layout.sessionMetaFile(sessionId), updated);
    }

    void deleteSession(String sessionId) {
        Path dir = layout.sessionDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot delete session " + sessionId, e);
        }
    }

    /**
     * 启动恢复：扫描会话目录。
     * <ul>
     *     <li>ACTIVE 会话保留，可继续使用（中断恢复）；</li>
     *     <li>COMPLETED/ABORTED 的残留目录删除。</li>
     * </ul>
     */
    List<UploadSessionMeta> recoverSessions() {
        Path sessionsDir = layout.sessionsDir();
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<UploadSessionMeta> recovered;
        try (Stream<Path> dirs = Files.list(sessionsDir).filter(Files::isDirectory)) {
            recovered = dirs.map(this::recoverOne).flatMap(List::stream).toList();
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "session recovery failed", e);
        }
        return recovered;
    }

    private List<UploadSessionMeta> recoverOne(Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        Path metaFile = sessionDir.resolve("session.json");
        if (!Files.exists(metaFile)) {
            deleteSession(sessionId);
            return List.of();
        }
        UploadSessionMeta meta;
        try (InputStream in = Files.newInputStream(metaFile)) {
            meta = mapper.readValue(in, UploadSessionMeta.class);
        } catch (IOException e) {
            deleteSession(sessionId);
            return List.of();
        }
        if (meta.state() == UploadState.ACTIVE) {
            return List.of(meta);
        }
        deleteSession(sessionId);
        return List.of();
    }

    private void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writeValue(out, value);
            }
            FileDurability.fsync(tmp);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            FileDurability.fsyncDir(file.getParent());
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot write " + file, e);
        }
    }
}
