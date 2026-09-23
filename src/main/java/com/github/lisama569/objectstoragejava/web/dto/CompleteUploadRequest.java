package com.github.lisama569.objectstoragejava.web.dto;

import java.util.List;

/**
 * 完成上传请求：全部分块引用（按上传顺序）与整个对象内容的期望校验和。
 */
public record CompleteUploadRequest(List<PartRefDto> parts, String checksum) {
}
