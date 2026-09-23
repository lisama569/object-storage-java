package com.github.lisama569.objectstoragejava.dto;

/** Result of initialising a multipart upload. */
public record UploadInitResponse(String objectName, String uploadId) {
}
