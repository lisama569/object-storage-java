package com.github.lisama569.objectstoragejava.exception;

/** Request conflicts with the current version state (HTTP 409). */
public class VersionConflictException extends StorageException {
    public VersionConflictException(String message) {
        super(message);
    }
}
