package com.github.lisama569.objectstoragejava.model;

/**
 * 一个分块写入内容寻址存储后的结果。
 *
 * @param checksum 服务端实际计算的 SHA-256
 * @param size     字节数
 */
public record StoredChunk(String checksum, long size) {
}
