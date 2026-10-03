package com.github.lisama569.objectstoragejava.dto;

/** Counters describing one garbage-collection pass (idempotent and retry-safe). */
public record GcReport(
        long blobsExamined,
        long liveReferences,
        long candidatesIdentified,
        long quarantined,
        long deleted,
        long restored,
        long staleQuarantineSwept,
        long durationMillis) {
}
