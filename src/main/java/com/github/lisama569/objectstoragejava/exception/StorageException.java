package com.github.lisama569.objectstoragejava.exception;

/** Base class for all object-storage failures. */
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
