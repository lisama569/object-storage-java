package com.github.lisama569.objectstoragejava.web.dto;

/**
 * 完成上传请求中的分块引用。
 */
public record PartRefDto(int partNumber, String checksum) {
}
