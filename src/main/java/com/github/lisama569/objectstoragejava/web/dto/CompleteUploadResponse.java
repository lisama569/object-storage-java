package com.github.lisama569.objectstoragejava.web.dto;

import java.util.List;

/**
 * 完成上传的响应：版本号、整体校验和、是否去重、版本判定依据、分块清单。
 */
public record CompleteUploadResponse(String objectKey,
                                     int version,
                                     String checksum,
                                     long size,
                                     boolean deduplicated,
                                     String decision,
                                     List<PartRefDto> parts) {
}
