package com.github.lisama569.objectstoragejava.exception;

/** Raised when a requested byte range is malformed or cannot be satisfied by the version size. */
public class InvalidRangeException extends StorageException {
    public InvalidRangeException(String message) {
        super(message);
    }
}
