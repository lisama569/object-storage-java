package com.github.lisama569.objectstoragejava.storage;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.ErrorCode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容寻址存储（Content-Addressed Store）。
 *
 * <p>分块以其 SHA-256 为唯一名存放；相同内容永远映射到同一个 blob，
 * 因此重复上传、并发上传相同分块都不会产生冗余字节。
 * 写入流程：先在 tmp 目录落盘并实时计算哈希 → 与期望哈希比对 →
 * 原子移动到 blobs 最终位置。移动前再次确认目标不存在（去重）。
 */
final class ContentAddressedStore {

    private final StorageLayout layout;

    /** 同一校验和的写移动操作串行化，避免并发重复移动。 */
    private final ConcurrentHashMap<String, Object> blobLocks = new ConcurrentHashMap<>();

    ContentAddressedStore(StorageLayout layout) {
        this.layout = layout;
    }

    /**
     * 存储一个分块。
     *
     * @param content          分块内容流（会被读取，不负责关闭）
     * @param expectedChecksum 客户端期望的 SHA-256；非空时必须与服务端计算结果一致
     * @return 实际校验和与字节数
     */
    StoredBlob store(InputStream content, String expectedChecksum) {
        Path tmpFile;
        String actualChecksum;
        long size;
        try {
            Files.createDirectories(layout.tmpDir());
            tmpFile = Files.createTempFile(layout.tmpDir(), "chunk-", ".tmp");
            MessageDigest digest = Checksums.sha256();
            try (DigestInputStream in = new DigestInputStream(new BufferedInputStream(content), digest)) {
                size = Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            }
            actualChecksum = HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "failed to stage chunk", e);
        }

        if (!Checksums.isSha256Hex(actualChecksum)) {
            quietlyDelete(tmpFile);
            throw new StorageException(ErrorCode.CHUNK_CHECKSUM_MISMATCH,
                    "server failed to derive a valid chunk checksum");
        }
        if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            quietlyDelete(tmpFile);
            throw new StorageException(ErrorCode.CHUNK_CHECKSUM_MISMATCH,
                    "chunk checksum mismatch: expected=" + expectedChecksum
                            + ", actual=" + actualChecksum);
        }

        Path finalFile = moveIntoBlobs(tmpFile, actualChecksum);
        try {
            size = Files.size(finalFile);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot stat blob " + actualChecksum, e);
        }
        return new StoredBlob(actualChecksum, size);
    }

    /**
     * 原子地把临时分块移入 blobs；若相同内容已存在（去重/并发），直接复用并删除临时文件。
     */
    private Path moveIntoBlobs(Path tmpFile, String checksum) {
        Object lock = blobLocks.computeIfAbsent(checksum, k -> new Object());
        try {
            synchronized (lock) {
                Path finalFile = layout.blobFile(checksum);
                if (Files.exists(finalFile)) {
                    // 内容相同的分块已存在：复用，不产生冗余存储
                    quietlyDelete(tmpFile);
                    return finalFile;
                }
                Files.createDirectories(finalFile.getParent());
                Path staged = tmpFile.resolveSibling(checksum + ".staged");
                Files.move(tmpFile, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                FileDurability.fsync(staged);
                try {
                    Files.move(staged, finalFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    if (Files.exists(finalFile)) {
                        // 并发情况下另一写入者已发布相同 blob
                        quietlyDelete(staged);
                        return finalFile;
                    }
                    quietlyDelete(staged);
                    throw new StorageException(ErrorCode.IO_ERROR,
                            "failed to publish blob " + checksum, atomicFailed);
                }
                FileDurability.fsyncDir(finalFile.getParent());
                return finalFile;
            }
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "failed to place blob " + checksum, e);
        } finally {
            blobLocks.remove(checksum, lock);
        }
    }

    boolean exists(String checksum) {
        return Files.exists(layout.blobFile(checksum));
    }

    InputStream open(String checksum) {
        Path file = layout.blobFile(checksum);
        if (!Files.exists(file)) {
            throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                    "referenced chunk is missing from content store: " + checksum);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot open blob " + checksum, e);
        }
    }

    /**
     * 按顺序拼接一组 blob，计算整体 SHA-256 与总大小。
     */
    CombinedHash combine(List<String> orderedChecksums) {
        MessageDigest digest = Checksums.sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        for (String checksum : orderedChecksums) {
            Path file = layout.blobFile(checksum);
            if (!Files.exists(file)) {
                throw new StorageException(ErrorCode.MANIFEST_INCOMPLETE,
                        "referenced chunk is missing from content store: " + checksum);
            }
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    total += read;
                }
            } catch (IOException e) {
                throw new StorageException(ErrorCode.IO_ERROR, "cannot read blob " + checksum, e);
            }
        }
        return new CombinedHash(HexFormat.of().formatHex(digest.digest()), total);
    }

    /** 按顺序把一组 blob 拷贝到输出流。 */
    long copyCombined(List<String> orderedChecksums, java.io.OutputStream out) {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        for (String checksum : orderedChecksums) {
            try (InputStream in = open(checksum)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                }
            } catch (IOException e) {
                throw new StorageException(ErrorCode.IO_ERROR, "cannot stream blob " + checksum, e);
            }
        }
        return total;
    }

    private static void quietlyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响正确性，启动恢复会统一清理 tmp
        }
    }

    /** 分块落盘结果。 */
    record StoredBlob(String checksum, long size) {
    }

    /** 多块拼接后的整体哈希与大小。 */
    record CombinedHash(String checksum, long size) {
    }
}
