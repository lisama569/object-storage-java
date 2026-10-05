package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;

/**
 * Inclusive byte range {@code [start, end]} within one object version.
 * Both offsets are zero-based and counted across the concatenation of the version's parts.
 *
 * @param start inclusive zero-based start offset
 * @param end   inclusive zero-based end offset ({@code 0 <= start <= end < size})
 */
public record ByteRange(long start, long end) {

    /** Number of bytes in the range (inclusive endpoints). */
    public long length() {
        return end - start + 1;
    }

    /**
     * Parse one RFC 7233 {@code bytes=} range-spec against a known content length.
     * <p>
     * Accepted forms (single range only):
     * <ul>
     *   <li>{@code bytes=START-END} — inclusive range, e.g. {@code bytes=0-99}</li>
     *   <li>{@code bytes=START-}    — open-ended range from START to the last byte</li>
     *   <li>{@code bytes=-SUFFIX}   — the last SUFFIX bytes</li>
     * </ul>
     * Multi-range specs, non-numeric/negative offsets and ranges that do not intersect the
     * version's bytes are rejected with {@link InvalidRangeException} rather than being clamped
     * to empty or silently expanded to the whole object.
     *
     * @param rangeHeader raw header value (may be {@code null})
     * @param size        exact byte length of the version the range is applied to
     * @return {@code null} when no range header was supplied (full-object read); otherwise the
     *         validated inclusive range
     */
    public static ByteRange parse(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        if (size <= 0) {
            throw new InvalidRangeException(
                    "Range not satisfiable: object version is empty (" + size + " bytes)");
        }
        String value = rangeHeader.trim();
        if (!value.startsWith("bytes=")) {
            throw new InvalidRangeException("Malformed Range header (must start with 'bytes='): " + rangeHeader);
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            throw new InvalidRangeException(
                    "Malformed Range header: exactly one byte range is supported, got " + rangeHeader);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new InvalidRangeException("Malformed Range header (missing '-'): " + rangeHeader);
        }
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();

        long start;
        long end;
        if (left.isEmpty()) {
            // Suffix form: bytes=-N -> last N bytes.
            if (right.isEmpty() || right.startsWith("+") || right.startsWith("-")) {
                throw new InvalidRangeException("Malformed suffix range: " + rangeHeader);
            }
            long suffix = parseNonNegative(right, rangeHeader);
            if (suffix == 0) {
                throw new InvalidRangeException(
                        "Range not satisfiable: suffix length must be greater than 0: " + rangeHeader);
            }
            start = Math.max(0, size - suffix);
            end = size - 1;
        } else {
            if (left.startsWith("+") || left.startsWith("-") || right.startsWith("+") || right.startsWith("-")) {
                throw new InvalidRangeException("Malformed byte range (negative offsets are not allowed): "
                        + rangeHeader);
            }
            start = parseNonNegative(left, rangeHeader);
            if (start >= size) {
                throw new InvalidRangeException(
                        "Range not satisfiable: start offset " + start + " >= object size " + size);
            }
            if (right.isEmpty()) {
                // Open-ended form: bytes=START- -> to the last byte.
                end = size - 1;
            } else {
                end = parseNonNegative(right, rangeHeader);
                if (end < start) {
                    throw new InvalidRangeException(
                            "Malformed byte range: end " + end + " precedes start " + start);
                }
                if (end >= size) {
                    throw new InvalidRangeException(
                            "Range not satisfiable: end offset " + end + " >= object size " + size);
                }
            }
        }
        return new ByteRange(start, end);
    }

    private static long parseNonNegative(String digits, String raw) {
        try {
            long value = Long.parseLong(digits);
            if (value < 0) {
                throw new InvalidRangeException("Malformed byte range (negative offset): " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidRangeException("Malformed byte range (offsets must be integers): " + raw);
        }
    }
}
