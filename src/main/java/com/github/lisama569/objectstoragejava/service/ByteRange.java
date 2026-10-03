package com.github.lisama569.objectstoragejava.service;

/**
 * Inclusive byte range {@code [start, end]} within one object version.
 * Both offsets are zero-based and counted across the concatenation of the version's parts.
 */
public record ByteRange(long start, long end) {

    /** Number of bytes in the range (inclusive endpoints). */
    public long length() {
        return end - start + 1;
    }

    /**
     * Parse a single RFC 7233 {@code bytes=} range against a known content length.
     * Skeleton: filled in once the read pipeline is implemented.
     */
    public static ByteRange parse(String rangeHeader, long size) {
        return null;
    }
}
