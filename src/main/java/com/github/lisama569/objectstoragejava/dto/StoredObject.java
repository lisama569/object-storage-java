package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/**
 * Metadata describing one committed, immutable object version.
 *
 * @param checksum        chained manifest checksum: SHA-256 of the ordered part-checksum hex strings
 *                        (used to validate the completion manifest; depends on the chosen part layout)
 * @param contentChecksum SHA-256 over the concatenated raw object bytes — independent of how the
 *                        upload was split into parts; this is the identity used for duplicate detection
 */
public record StoredObject(
        String objectName,
        long version,
        String checksum,
        String contentChecksum,
        long size,
        List<String> partChecksums,
        boolean deduplicated) {
}
