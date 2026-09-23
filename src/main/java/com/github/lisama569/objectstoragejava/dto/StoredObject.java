package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/** Metadata describing one committed, immutable object version. */
public record StoredObject(
        String objectName,
        long version,
        String checksum,
        long size,
        List<String> partChecksums,
        boolean deduplicated) {
}
