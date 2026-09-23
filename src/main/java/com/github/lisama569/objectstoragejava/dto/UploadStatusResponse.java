package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/** Status of an in-progress multipart upload (used for resumption). */
public record UploadStatusResponse(
        String objectName,
        String uploadId,
        List<PartStatus> parts) {

    /** One already-uploaded part. */
    public record PartStatus(int partNumber, String checksum, long size) {
    }
}
