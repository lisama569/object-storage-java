package com.github.lisama569.objectstoragejava.web.dto;

/**
 * 统一错误响应体：可区分的错误码 + 说明。
 */
public record ErrorResponse(String code, String message) {
}
