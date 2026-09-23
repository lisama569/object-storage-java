package com.github.lisama569.objectstoragejava.model;

/**
 * 清单中的一个分块引用：分块序号 + 分块内容的 SHA-256 + 字节数。
 *
 * @param partNumber 从 1 开始、连续的分块序号
 * @param checksum   该分块内容的 SHA-256（十六进制）
 * @param size       该分块字节数
 */
public record ManifestPart(int partNumber, String checksum, long size) {
}
