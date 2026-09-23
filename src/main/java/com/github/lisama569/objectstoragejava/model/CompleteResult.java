package com.github.lisama569.objectstoragejava.model;

/**
 * 完成上传（发布版本）的结果。
 *
 * @param manifest      最终生效的版本清单
 * @param deduplicated  是否因内容与当前版本完全相同而未产生新版本
 * @param decision      版本判定依据的人类可读说明（用于日志/响应解释）
 */
public record CompleteResult(VersionManifest manifest,
                             boolean deduplicated,
                             String decision) {
}
