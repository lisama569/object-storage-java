package com.github.lisama569.objectstoragejava.exception;

/** Raised when an object or multipart upload does not exist. */
public class UploadNotFoundException extends StorageException {
    public UploadNotFoundException(String message) {
        super(message);
    }
}
