package com.github.lisama569.objectstoragejava.dto;

/** Result of uploading a single chunk. */
public record ChunkUploadResponse(
        String objectName,
        String uploadId,
        int partNumber,
        String checksum,
        boolean alreadyPresent,
        long size) {
}
