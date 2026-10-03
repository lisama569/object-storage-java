package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.dto.GcReport;
import org.springframework.stereotype.Service;

/**
 * Safe, retryable reclamation of content-addressed blobs that are no longer referenced by any
 * readable object version. Skeleton; the two-phase quarantine protocol is implemented later.
 */
@Service
public class StorageReclamationService {

    private final StorageLayout layout;
    private final ReadGate readGate;

    public StorageReclamationService(StorageLayout layout, ReadGate readGate) {
        this.layout = layout;
        this.readGate = readGate;
    }

    /** Run one complete garbage-collection pass. */
    public GcReport collectGarbage() {
        return null;
    }

    /**
     * Run one pass with a minimum un-referenced age for candidate blobs (milliseconds).
     * Kept package-private-friendly for fast deterministic tests.
     */
    public GcReport collectGarbage(long minAgeMillis) {
        return null;
    }
}
