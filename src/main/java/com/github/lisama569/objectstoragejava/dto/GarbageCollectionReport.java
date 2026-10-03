package com.github.lisama569.objectstoragejava.dto;

import java.util.List;

/** Result of one garbage-collection pass. Skeleton. */
public record GarbageCollectionReport(
        long totalBlobs,
        long referencedBlobs,
        long deletedBlobs,
        long skippedInUse,
        long skippedTooYoung,
        long deletionFailures,
        long reclaimedBytes,
        List<String> deletedChecksums) {
}
