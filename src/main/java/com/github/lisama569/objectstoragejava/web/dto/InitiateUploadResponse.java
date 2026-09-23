package com.github.lisama569.objectstoragejava.web.dto;

/**
 * 初始化分片上传会话的响应。
 */
public record InitiateUploadResponse(String sessionId, String objectKey, String state, long createdAt) {
}
