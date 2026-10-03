package com.github.lisama569.objectstoragejava.dto;

import com.github.lisama569.objectstoragejava.exception.InvalidManifestException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;

/**
 * An inclusive byte range [start, end] within an object version.
 * <p>
 * Only a single {@code bytes=} interval is supported (RFC 9110 forms):
 * <ul>
 *     <li>{@code bytes=START-END}  — explicit inclusive interval;</li>
 *     <li>{@code bytes=START-}     — from START to the last byte;</li>
 *     <li>{@code bytes=-SUFFIX}    — the last SUFFIX bytes.</li>
 * </ul>
 * Multiple intervals or other units are rejected with {@link InvalidManifestException} (HTTP 400).
 */
public record ByteRange(long start, long end) {

    public ByteRange {
        if (start < 0 || end < start) {
            throw new InvalidManifestException("Invalid byte range: start=" + start + ", end=" + end);
        }
    }

    public long length() {
        return end - start + 1;
    }

    /**
     * Parse a {@code Range} header against a known object size and normalise it to an inclusive
     * [start, end] clipped to {@code [0, objectSize-1]}.
     *
     * @return null when the header is absent
     */
    public static ByteRange parse(String headerValue, long objectSize) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String value = headerValue.trim();
        if (!value.startsWith("bytes=")) {
            throw new InvalidManifestException("Only byte ranges are supported, got Range: " + headerValue);
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            throw new InvalidManifestException(
                    "Exactly one byte range is supported, got Range: " + headerValue);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new InvalidManifestException("Malformed byte range: " + headerValue);
        }
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();

        long start;
        long end;
        if (left.isEmpty()) {
            // Suffix form: bytes=-N -> the last N bytes.
            if (right.isEmpty()) {
                throw new InvalidManifestException("Malformed suffix byte range: " + headerValue);
            }
            long suffix = parseNonNegative(right, headerValue);
            if (suffix == 0) {
                throw new RangeNotSatisfiableException(
                        "Cannot return a zero-length suffix of an object of size " + objectSize, objectSize);
            }
            start = Math.max(0, objectSize - suffix);
            end = objectSize - 1;
        } else {
            start = parseNonNegative(left, headerValue);
            if (start >= objectSize) {
                throw new RangeNotSatisfiableException(
                        "Range starts at byte " + start + " but object size is " + objectSize, objectSize);
            }
            end = right.isEmpty() ? objectSize - 1 : parseNonNegative(right, headerValue);
            if (end < start) {
                throw new InvalidManifestException(
                        "Range end precedes start in Range: " + headerValue);
            }
            // Clipping an explicit over-long end to the object size is allowed by RFC 9110.
            if (end >= objectSize) {
                end = objectSize - 1;
            }
        }
        return new ByteRange(start, end);
    }

    private static long parseNonNegative(String s, String raw) {
        try {
            long v = Long.parseLong(s);
            if (v < 0) {
                throw new InvalidManifestException("Negative byte offset in Range: " + raw);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new InvalidManifestException("Malformed byte range: " + raw);
        }
    }
}
