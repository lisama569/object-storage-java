package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/** Request body for multipart upload completion. */
public record ManifestRequest(String checksum, List<ManifestEntry> parts) {
}
