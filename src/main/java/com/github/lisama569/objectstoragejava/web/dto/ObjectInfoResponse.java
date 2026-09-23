package com.github.lisama569.objectstoragejava.web.dto;

/**
 * 对象当前版本信息。
 */
public record ObjectInfoResponse(String objectKey,
                                 int currentVersion,
                                 String checksum,
                                 long size,
                                 int partCount,
                                 long createdAt) {
}
