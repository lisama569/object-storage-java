package com.github.lisama569.objectstoragejava.exception;

/**
 * Raised when a syntactically valid byte range cannot be served by the requested version
 * (start offset beyond the content, or suffix larger than the object).
 * Maps to HTTP 416 Range Not Satisfiable, distinct from a malformed Range header (400).
 */
public class RangeNotSatisfiableException extends InvalidRangeException {
    public RangeNotSatisfiableException(String message) {
        super(message);
    }
}
