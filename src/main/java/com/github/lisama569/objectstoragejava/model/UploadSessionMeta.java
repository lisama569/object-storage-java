package com.github.lisama569.objectstoragejava.model;

/**
 * 分片上传会话的持久化元数据。
 *
 * @param sessionId 会话 ID
 * @param objectKey 目标对象键
 * @param createdAt 创建时间（epoch millis）
 * @param state     会话状态
 */
public record UploadSessionMeta(String sessionId,
                                String objectKey,
                                long createdAt,
                                UploadState state) {
}
