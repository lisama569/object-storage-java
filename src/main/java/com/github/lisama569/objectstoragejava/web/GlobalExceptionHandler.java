package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：每类存储错误返回其专属、可区分的错误码与对应 HTTP 状态，
 * 绝不静默返回 200。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode().httpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("storage error [{}] -> {}: {}", ex.getCode(), status.value(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "malformed request body: " + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("IO_ERROR", ex.getMessage()));
    }
}
