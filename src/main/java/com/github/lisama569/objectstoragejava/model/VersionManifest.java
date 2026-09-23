package com.github.lisama569.objectstoragejava.model;

import java.util.List;

/**
 * 一个不可变对象版本的完整元数据（清单）。
 *
 * @param version     单调递增的版本号（从 1 开始）
 * @param objectKey   对象键
 * @param checksum    整个对象内容（各分块按序拼接）的 SHA-256
 * @param size        对象总字节数
 * @param parts       有序分块引用列表
 * @param createdAt   版本创建时间（epoch millis）
 * @param completedAt 版本发布完成时间（epoch millis）
 */
public record VersionManifest(int version,
                              String objectKey,
                              String checksum,
                              long size,
                              List<ManifestPart> parts,
                              long createdAt,
                              long completedAt) {
}
