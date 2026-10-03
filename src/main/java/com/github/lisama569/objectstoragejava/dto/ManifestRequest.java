package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/**
 * Request body for multipart upload completion.
 *
 * @param checksum        required chained object checksum (layout-dependent)
 * @param parts           ordered part references
 * @param contentChecksum optional SHA-256 over the assembled object <em>bytes</em>, independent of
 *                        part boundaries; when present it is verified and used as the
 *                        chunking-independent duplicate basis
 */
public record ManifestRequest(String checksum, List<ManifestEntry> parts, String contentChecksum) {

    /** Backwards-compatible constructor (no contentChecksum claimed). */
    public ManifestRequest(String checksum, List<ManifestEntry> parts) {
        this(checksum, parts, null);
    }
}
