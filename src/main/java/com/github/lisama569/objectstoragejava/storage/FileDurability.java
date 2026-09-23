package com.github.lisama569.objectstoragejava.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件持久化辅助：fsync 文件与其所在目录（尽力而为，文件系统不支持时静默忽略）。
 */
final class FileDurability {

    private FileDurability() {
    }

    static void fsync(Path file) {
        try (var ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            if (ch instanceof java.nio.channels.FileChannel fc) {
                fc.force(true);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // 某些文件系统（如部分 tmpfs）不支持 fsync，属尽力而为
        }
    }

    static void fsyncDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var ch = Files.newByteChannel(dir, StandardOpenOption.READ)) {
            if (ch instanceof java.nio.channels.FileChannel fc) {
                fc.force(true);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // 目录 fsync 不被广泛支持，尽力而为
        }
    }
}
