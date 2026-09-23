package com.github.lisama569.objectstoragejava.storage;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.ErrorCode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;

/**
 * 磁盘布局与对象键编码。
 *
 * <pre>
 * root/
 *   blobs/&lt;xx&gt;/&lt;sha256&gt;          内容寻址分块（xx 为校验和前 2 位，分桶）
 *   objects/&lt;xx&gt;/&lt;keyhex&gt;/
 *       versions/0000000001.json  不可变版本清单
 *       current                   当前版本号指针（原子移动发布）
 *       tmp/                      发布中的临时清单
 *   sessions/&lt;sessionId&gt;/
 *       session.json              会话元数据
 *       parts/&lt;partNumber&gt;.json   已上传分块引用
 *   tmp/                          分块落盘临时区（启动时清空）
 * </pre>
 *
 * 对象键使用 UTF-8 全量十六进制编码并按前 2 位分桶，
 * 天然杜绝 {@code ..}、{@code /} 等路径穿越。
 */
final class StorageLayout {

    private final Path root;
    private final Path blobs;
    private final Path objects;
    private final Path sessions;
    private final Path tmp;

    StorageLayout(String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        this.blobs = this.root.resolve("blobs");
        this.objects = this.root.resolve("objects");
        this.sessions = this.root.resolve("sessions");
        this.tmp = this.root.resolve("tmp");
    }

    Path root() {
        return root;
    }

    Path blobsDir() {
        return blobs;
    }

    Path objectsDir() {
        return objects;
    }

    Path sessionsDir() {
        return sessions;
    }

    Path tmpDir() {
        return tmp;
    }

    /**
     * 校验对象键：非空、长度受限、不允许控制字符与反斜杠。
     */
    String validateKey(String objectKey) {
        if (objectKey == null || objectKey.isEmpty()) {
            throw new StorageException(ErrorCode.INVALID_OBJECT_KEY, "object key must not be empty");
        }
        if (objectKey.length() > 512) {
            throw new StorageException(ErrorCode.INVALID_OBJECT_KEY, "object key too long (max 512)");
        }
        String trimmed = objectKey.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            throw new StorageException(ErrorCode.INVALID_OBJECT_KEY, "invalid object key: " + objectKey);
        }
        // 显式拒绝任何 ".." 路径段（叠加全量十六进制编码，双重杜绝路径穿越）
        String normalized = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new StorageException(ErrorCode.INVALID_OBJECT_KEY,
                        "object key must not contain '..' segments: " + objectKey);
            }
        }
        for (int i = 0; i < objectKey.length(); i++) {
            char c = objectKey.charAt(i);
            if (c == '\\' || Character.isISOControl(c)) {
                throw new StorageException(ErrorCode.INVALID_OBJECT_KEY,
                        "object key contains illegal character at " + i);
            }
        }
        return objectKey;
    }

    /** 对象键 → 十六进制编码目录名。 */
    static String encodeKey(String objectKey) {
        return HexFormat.of().formatHex(objectKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 某对象的根目录：objects/&lt;xx&gt;/&lt;keyhex&gt;。 */
    Path objectDir(String objectKey) {
        String hex = encodeKey(validateKey(objectKey));
        return objects.resolve(hex.substring(0, 2)).resolve(hex);
    }

    Path versionsDir(String objectKey) {
        return objectDir(objectKey).resolve("versions");
    }

    Path currentPointer(String objectKey) {
        return objectDir(objectKey).resolve("current");
    }

    Path objectTmpDir(String objectKey) {
        return objectDir(objectKey).resolve("tmp");
    }

    /** 版本清单文件名：10 位零填充，保证字典序与版本号顺序一致。 */
    static String versionFileName(int version) {
        return String.format("%010d.json", version);
    }

    Path versionFile(String objectKey, int version) {
        return versionsDir(objectKey).resolve(versionFileName(version));
    }

    /** 内容寻址分块路径：blobs/&lt;xx&gt;/&lt;sha256&gt;。 */
    Path blobFile(String checksum) {
        return blobs.resolve(checksum.substring(0, 2)).resolve(checksum);
    }

    Path sessionDir(String sessionId) {
        return sessions.resolve(validateSessionId(sessionId));
    }

    Path sessionMetaFile(String sessionId) {
        return sessionDir(sessionId).resolve("session.json");
    }

    Path sessionPartsDir(String sessionId) {
        return sessionDir(sessionId).resolve("parts");
    }

    Path sessionPartFile(String sessionId, int partNumber) {
        if (partNumber < 1 || partNumber > 100_000) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "part number out of range: " + partNumber);
        }
        return sessionPartsDir(sessionId).resolve(partNumber + ".json");
    }

    private String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.length() < 8 || sessionId.length() > 64) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "invalid session id");
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "invalid session id");
            }
        }
        return sessionId;
    }
}
