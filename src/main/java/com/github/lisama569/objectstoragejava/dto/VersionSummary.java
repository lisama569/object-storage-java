package com.github.lisama569.objectstoragejava.dto;

/** Compact summary of one committed version (used by the version listing). */
public record VersionSummary(
        String objectName,
        long version,
        long size,
        String checksum,
        String contentChecksum) {

    public static VersionSummary from(StoredObject v) {
        return new VersionSummary(v.objectName(), v.version(), v.size(), v.checksum(), v.contentChecksum());
    }
}
