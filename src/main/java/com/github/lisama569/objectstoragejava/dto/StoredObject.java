package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/**
 * Metadata describing one committed, immutable object version.
 *
 * @param checksum        chained, part-layout-dependent object checksum
 *                        ({@code SHA256(concat(partHex...))}); stable for existing data
 * @param contentChecksum checksum of the raw concatenated object bytes, independent of how the
 *                        upload was split into parts; this is the basis for duplicate detection
 * @param partChecksums   ordered content-addressed blob references backing this version
 * @param partSizes       byte length of each referenced part (drives cross-part range reads)
 * @param deduplicated    true when completion reused an existing version instead of publishing
 * @param dedupBasis      human-readable explanation of how {@code deduplicated} was decided
 */
public record StoredObject(
        String objectName,
        long version,
        String checksum,
        long size,
        List<String> partChecksums,
        boolean deduplicated,
        String contentChecksum,
        List<Long> partSizes,
        String dedupBasis) {

    /** Backwards-compatible constructor for callers that do not track the new fields. */
    public StoredObject(String objectName, long version, String checksum, long size,
                        List<String> partChecksums, boolean deduplicated) {
        this(objectName, version, checksum, size, partChecksums, deduplicated, null, null, null);
    }
}
