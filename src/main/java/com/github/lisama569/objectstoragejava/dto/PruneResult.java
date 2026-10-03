package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/**
 * Outcome of pruning an object's historical versions.
 * Blobs are never deleted here; they become garbage-collection candidates.
 */
public record PruneResult(
        String objectName,
        long currentVersion,
        List<Long> retainedVersions,
        List<Long> removedVersions) {
}
