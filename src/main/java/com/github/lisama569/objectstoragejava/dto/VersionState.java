package com.github.lisama569.objectstoragejava.dto;

/** Accessibility state of a stored version. */
public enum VersionState {
    /** Reachable through the current pointer. */
    CURRENT,
    /** Historical version, still fully readable. */
    LIVE,
    /** Explicitly retired; metadata retained as a tombstone, bytes may be garbage-collected. */
    RETIRED
}
