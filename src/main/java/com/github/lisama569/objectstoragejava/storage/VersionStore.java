package com.github.lisama569.objectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.ErrorCode;
import com.github.lisama569.objectstoragejava.model.ManifestPart;
import com.github.lisama569.objectstoragejava.model.VersionManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 版本清单存储：负责版本号分配、清单持久化与 current 指针的原子发布。
 *
 * <p>关键不变量（由调用方配合对象级锁保证）：
 * <ul>
 *     <li>版本号只增不减，按发布顺序分配；</li>
 *     <li>清单文件一经发布即不可变；</li>
 *     <li>{@code current} 指针通过"同目录原子移动 + 最后切换"发布，
 *     读者要么看到旧版本完整清单，要么看到新版本完整清单，绝不看到半成品；</li>
 *     <li>发布失败只留下 tmp/ 或 stray 清单，不会改动旧 current，旧版本始终可读。</li>
 * </ul>
 */
final class VersionStore {

    private static final String TMP_MANIFEST_PREFIX = "publish-";

    private final StorageLayout layout;
    private final ObjectMapper mapper;

    VersionStore(StorageLayout layout, ObjectMapper mapper) {
        this.layout = layout;
        this.mapper = mapper;
    }

    /** 当前版本号；对象或指针不存在时返回 0。 */
    int currentVersionNumber(String objectKey) {
        Path pointer = layout.currentPointer(objectKey);
        if (!Files.exists(pointer)) {
            return 0;
        }
        try {
            String text = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(text);
        } catch (IOException | NumberFormatException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "corrupt current pointer for " + objectKey, e);
        }
    }

    /** 当前版本清单；不存在返回 null。 */
    VersionManifest readCurrent(String objectKey) {
        int number = currentVersionNumber(objectKey);
        if (number == 0) {
            return null;
        }
        return readVersion(objectKey, number);
    }

    /** 读取指定版本清单；版本不存在抛出 {@link ErrorCode#VERSION_NOT_FOUND}。 */
    VersionManifest readVersion(String objectKey, int number) {
        Path file = layout.versionFile(objectKey, number);
        if (!Files.exists(file)) {
            throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                    "version " + number + " of object '" + objectKey + "' not found");
        }
        try (InputStream in = Files.newInputStream(file)) {
            VersionManifest manifest = mapper.readValue(in, VersionManifest.class);
            if (manifest.version() != number) {
                throw new StorageException(ErrorCode.IO_ERROR,
                        "manifest file/version mismatch: " + file);
            }
            return manifest;
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "cannot read manifest " + file, e);
        }
    }

    /**
     * 原子发布一个新版本。
     *
     * @param nextVersion 调用方在对象锁内确定的下一版本号
     * @param manifest    待持久化的清单（其 version 必须等于 nextVersion）
     */
    void publish(String objectKey, int nextVersion, VersionManifest manifest) {
        Path versionsDir = layout.versionsDir(objectKey);
        Path objectTmp = layout.objectTmpDir(objectKey);
        try {
            Files.createDirectories(versionsDir);
            Files.createDirectories(objectTmp);

            // 1. 清单先写到对象自己的 tmp 目录并 fsync
            Path tmpManifest = Files.createTempFile(objectTmp, TMP_MANIFEST_PREFIX, ".json");
            try (var out = Files.newOutputStream(tmpManifest,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writeValue(out, manifest);
            }
            FileDurability.fsync(tmpManifest);

            // 2. 清单移入 versions 目录（不可变名）；旧版本文件绝不改动
            Path versionFile = layout.versionFile(objectKey, nextVersion);
            if (Files.exists(versionFile)) {
                Files.deleteIfExists(tmpManifest);
                throw new StorageException(ErrorCode.IO_ERROR,
                        "version file already exists: " + nextVersion);
            }
            try {
                Files.move(tmpManifest, versionFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tmpManifest);
                throw new StorageException(ErrorCode.IO_ERROR, "failed to place manifest", e);
            }
            FileDurability.fsyncDir(versionsDir);

            // 3. 最后原子切换 current 指针：在此之前读者看到的仍是旧版本。
            //    临时指针文件必须对每次发布唯一，否则并发发布会共用同一个 current.next，
            //    导致较晚完成的发布用较小版本号覆盖指针（版本号瞬时回退）。
            Path pointer = layout.currentPointer(objectKey);
            Path tmpPointer = Files.createTempFile(objectTmp, "current-", ".next");
            Files.writeString(tmpPointer, Integer.toString(nextVersion), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            FileDurability.fsync(tmpPointer);
            Files.move(tmpPointer, pointer,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            FileDurability.fsyncDir(objectTmp.getParent());
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "failed to publish version", e);
        }
    }

    /**
     * 启动恢复：
     * <ul>
     *     <li>删除对象 tmp 目录中的发布残留；</li>
     *     <li>删除编号大于 current 指针的 stray 清单（崩溃在第 2 步、第 3 步之前），
     *         保证版本号连续、不留半发布状态。</li>
     * </ul>
     */
    int recoverStrayManifests() {
        int removed = 0;
        if (!Files.exists(layout.objectsDir())) {
            return removed;
        }
        try (Stream<Path> shards = Files.list(layout.objectsDir())) {
            for (Path shard : filterDirs(shards)) {
                try (Stream<Path> objs = Files.list(shard)) {
                    for (Path objectDir : filterDirs(objs)) {
                        removed += recoverOneObject(objectDir);
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException(ErrorCode.IO_ERROR, "recovery scan failed", e);
        }
        return removed;
    }

    private int recoverOneObject(Path objectDir) {
        int removed = 0;
        Path tmpDir = objectDir.resolve("tmp");
        if (Files.isDirectory(tmpDir)) {
            try (Stream<Path> entries = Files.list(tmpDir)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                    removed++;
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
        Path pointer = objectDir.resolve("current");
        int current = 0;
        if (Files.exists(pointer)) {
            try {
                current = Integer.parseInt(Files.readString(pointer).trim());
            } catch (NumberFormatException | IOException e) {
                current = 0;
            }
        }
        Path versionsDir = objectDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return removed;
        }
        try (Stream<Path> manifests = Files.list(versionsDir)) {
            List<Path> files = manifests
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path file : files) {
                int number = parseVersionNumber(file);
                if (number < 0 || number > current) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return removed;
    }

    private int parseVersionNumber(Path file) {
        String name = file.getFileName().toString();
        try {
            return Integer.parseInt(name.substring(0, name.length() - ".json".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<Path> filterDirs(Stream<Path> stream) {
        return stream.filter(Files::isDirectory).toList();
    }

    /** 构造分块引用（便捷方法）。 */
    static ManifestPart part(int number, String checksum, long size) {
        return new ManifestPart(number, checksum, size);
    }
}
