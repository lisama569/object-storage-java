package com.github.lisama569.objectstoragejava.exception;

/** Raised when an uploaded chunk or the assembled object fails checksum verification. */
public class ChecksumMismatchException extends StorageException {
    public ChecksumMismatchException(String message) {
        super(message);
    }
}
