package com.github.lisama569.objectstoragejava.exception;

/** Requested byte range cannot be served (HTTP 416). */
public class RangeNotSatisfiableException extends StorageException {

    private final long objectSize;

    public RangeNotSatisfiableException(String message, long objectSize) {
        super(message);
        this.objectSize = objectSize;
    }

    public long objectSize() {
        return objectSize;
    }
}
