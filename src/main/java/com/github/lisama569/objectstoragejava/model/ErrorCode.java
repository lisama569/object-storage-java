package com.github.lisama569.objectstoragejava.model;

/**
 * 可区分的错误原因。每种错误映射一个 HTTP 状态码，便于客户端区分处理。
 */
public enum ErrorCode {
    INVALID_OBJECT_KEY(400),
    BAD_REQUEST(400),
    OBJECT_NOT_FOUND(404),
    VERSION_NOT_FOUND(404),
    SESSION_NOT_FOUND(404),
    SESSION_ABORTED(409),
    CHUNK_CHECKSUM_MISMATCH(422),
    MANIFEST_MISSING(422),
    MANIFEST_INCOMPLETE(422),
    OBJECT_CHECKSUM_MISMATCH(422),
    IO_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
