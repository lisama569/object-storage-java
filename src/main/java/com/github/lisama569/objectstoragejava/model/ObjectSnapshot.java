package com.github.lisama569.objectstoragejava.model;

/**
 * 某一版本在单次读取中的一致快照：清单与其对应字节来自同一次 current 指针读取，
 * 不会出现"清单属于旧版本、字节属于新版本"的错配。
 *
 * @param manifest 版本清单
 * @param bytes    该版本经整体 SHA-256 校验通过的完整字节
 */
public record ObjectSnapshot(VersionManifest manifest, byte[] bytes) {
}
