package com.github.lisama569.objectstoragejava.dto;

/** One entry in the version listing of an object. */
public record VersionInfo(
        long version,
        String state,
        String checksum,
        String contentChecksum,
        long size,
        Long retiredAtEpochMillis) {
}
