package com.github.lisama569.objectstoragejava.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM-wide protection for blobs that are currently being read or being published.
 * <p>
 * A blob covered by an active lease or registered as "pending publication" is never a garbage
 * collection candidate, even if no readable version currently references it. Reference counting
 * makes nested/overlapping acquisitions safe (the last release, not the first, removes protection).
 * This closes two small windows:
 * <ul>
 *     <li><b>publication</b>: between materialising blobs and durably flipping the
 *     {@code current} pointer, a GC pass must not see the new blobs as unreferenced garbage;</li>
 *     <li><b>read snapshot</b>: between resolving a version snapshot and opening its blob streams
 *     (or while the client still streams them), concurrent retirement + GC must not delete them.</li>
 * </ul>
 * On POSIX systems deleting an already-open file keeps the reader's bytes intact as a second
 * line of defence; the lease removes even that reliance.
 */
@Component
public class BlobLeaseManager {

    /** A held protection set; close to release. */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static final Lease NOOP = () -> { };

    private final ConcurrentHashMap<String, AtomicInteger> protectedChecksums = new ConcurrentHashMap<>();

    public BlobLeaseManager() {
    }

    private void retain(Collection<String> checksums) {
        for (String checksum : checksums) {
            protectedChecksums.computeIfAbsent(checksum, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private void release(Collection<String> checksums) {
        for (String checksum : checksums) {
            protectedChecksums.computeIfPresent(checksum, (k, counter) ->
                    counter.decrementAndGet() <= 0 ? null : counter);
        }
    }

    /** Protect the given blob checksums for the lifetime of a read. */
    public Lease acquire(Collection<String> checksums) {
        if (checksums == null || checksums.isEmpty()) {
            return NOOP;
        }
        retain(checksums);
        return () -> release(checksums);
    }

    /** Register blobs that are about to be published by a new version (held until the pointer flip). */
    public void markPending(Collection<String> checksums) {
        if (checksums != null) {
            retain(checksums);
        }
    }

    /** Release publication registration after the new version is durably committed. */
    public void clearPending(Collection<String> checksums) {
        if (checksums != null) {
            release(checksums);
        }
    }

    /** Whether a blob must not be garbage-collected right now. */
    public boolean isProtected(String checksum) {
        return checksum != null && protectedChecksums.containsKey(checksum);
    }
}
