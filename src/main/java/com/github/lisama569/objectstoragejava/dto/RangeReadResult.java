package com.github.lisama569.objectstoragejava.dto;

import com.github.lisama569.objectstoragejava.service.ByteRange;

import java.io.InputStream;

/**
 * One consistent range-read result: the exact version metadata snapshot the bytes belong to,
 * the inclusive byte range served, and the verified byte stream.
 */
public record RangeReadResult(StoredObject version, ByteRange range, InputStream content) {
}
