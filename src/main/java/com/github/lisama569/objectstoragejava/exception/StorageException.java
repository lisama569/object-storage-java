package com.github.lisama569.objectstoragejava.exception;

import com.github.lisama569.objectstoragejava.model.ErrorCode;

/**
 * 存储服务统一异常，携带可区分的 {@link ErrorCode}。
 */
public class StorageException extends RuntimeException {

    private final ErrorCode code;

    public StorageException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public StorageException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
