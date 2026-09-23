package com.github.lisama569.objectstoragejava.exception;

/** Raised when a completion manifest is missing or references incomplete/missing parts. */
public class InvalidManifestException extends StorageException {
    public InvalidManifestException(String message) {
        super(message);
    }
}
