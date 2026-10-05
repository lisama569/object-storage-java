package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;

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
     * <p>
     * Supported forms (the three common single-range spellings):
     * <ul>
     *   <li>{@code bytes=START-END} — inclusive window, END clamped to {@code size-1};</li>
     *   <li>{@code bytes=START-}     — open-ended, from START to the last byte;</li>
     *   <li>{@code bytes=-SUFFIX}    — the final SUFFIX bytes of the content.</li>
     * </ul>
     * A malformed header (bad prefix, non-numeric, multiple/empty ranges) raises
     * {@link InvalidRangeException} (HTTP 400). A syntactically valid range that cannot be
     * satisfied — {@code START >= size}, or an empty object — raises
     * {@link RangeNotSatisfiableException} (HTTP 416).
     */
    public static ByteRange parse(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            throw new InvalidRangeException("Missing Range header; expected a single 'bytes=' range");
        }
        String header = rangeHeader.trim();
        if (!header.startsWith("bytes=")) {
            throw new InvalidRangeException(
                    "Unsupported or malformed Range header '" + rangeHeader + "' (expected 'bytes=...')");
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.isEmpty()) {
            throw new InvalidRangeException("Malformed Range header '" + rangeHeader + "': empty byte range");
        }
        // Only a single range is served; a comma introduces a multi-range request.
        if (spec.indexOf(',') >= 0) {
            throw new InvalidRangeException(
                    "Malformed Range header '" + rangeHeader + "': multiple ranges are not supported");
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new InvalidRangeException(
                    "Malformed Range header '" + rangeHeader + "': expected 'start-end'");
        }
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();

        if (left.isEmpty()) {
            // Suffix form: bytes=-N  -> last N bytes (N >= 1).
            if (right.isEmpty()) {
                throw new InvalidRangeException(
                        "Malformed Range header '" + rangeHeader + "': '-' needs an offset on one side");
            }
            long suffix = parseNonNegative(right, rangeHeader);
            if (suffix == 0) {
                throw new InvalidRangeException(
                        "Malformed Range header '" + rangeHeader + "': suffix length must be positive");
            }
            if (size <= 0) {
                throw new RangeNotSatisfiableException(
                        "Range '" + rangeHeader + "' not satisfiable: object is empty (size=0)");
            }
            long start = Math.max(0, size - suffix);
            return new ByteRange(start, size - 1);
        }

        long start = parseNonNegative(left, rangeHeader);
        if (start >= size) {
            throw new RangeNotSatisfiableException("Range '" + rangeHeader + "' not satisfiable: start offset "
                    + start + " is beyond object size " + size);
        }
        long end;
        if (right.isEmpty()) {
            // Open-ended form: bytes=START- -> through the last byte.
            end = size - 1;
        } else {
            end = parseNonNegative(right, rangeHeader);
            if (end < start) {
                throw new InvalidRangeException(
                        "Malformed Range header '" + rangeHeader + "': end offset precedes start offset");
            }
            // An end beyond the object is clamped to the last byte rather than failing.
            end = Math.min(end, size - 1);
        }
        return new ByteRange(start, end);
    }

    private static long parseNonNegative(String value, String rawHeader) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new InvalidRangeException(
                        "Malformed Range header '" + rawHeader + "': offsets must be non-negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InvalidRangeException(
                    "Malformed Range header '" + rawHeader + "': '" + value + "' is not a valid byte offset");
        }
    }
}
