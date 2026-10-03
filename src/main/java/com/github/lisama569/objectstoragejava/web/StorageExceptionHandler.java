package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.InvalidManifestException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import com.github.lisama569.objectstoragejava.exception.VersionConflictException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps storage failures to distinct HTTP status codes with explanatory bodies. */
@RestControllerAdvice
public class StorageExceptionHandler {

    @ExceptionHandler(UploadNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UploadNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<Map<String, String>> handleChecksum(ChecksumMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "checksum_mismatch", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidManifestException.class)
    public ResponseEntity<Map<String, String>> handleManifest(InvalidManifestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid_manifest", "message", e.getMessage()));
    }

    @ExceptionHandler(RangeNotSatisfiableException.class)
    public ResponseEntity<Map<String, String>> handleRange(RangeNotSatisfiableException e) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.objectSize())
                .body(Map.of("error", "range_not_satisfiable", "message", e.getMessage()));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "version_conflict", "message", e.getMessage()));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, String>> handleStorage(StorageException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "storage_error", "message", e.getMessage()));
    }
}
