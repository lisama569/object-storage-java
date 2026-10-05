package com.github.lisama569.objectstoragejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JVM-global read/write gate coordinating object readers (and publishers) with blob garbage
 * collection.
 * <p>
 * Unlike {@link java.util.concurrent.locks.ReentrantReadWriteLock}, leases here are
 * <b>not thread-bound</b>: a read lease may be acquired on the request thread and released on
 * the async servlet streaming thread when the response byte stream is closed.
 * <p>
 * Contract:
 * <ul>
 *   <li>a reader acquires a {@link ReadLease} <em>before</em> opening the blob files of one
 *       resolved version and releases it only after the returned byte stream is closed, so GC
 *       can never quarantine a blob while a read is in flight;</li>
 *   <li>a version publisher likewise holds a read lease for the whole materialise-blobs →
 *       write-version → flip-pointer sequence, guaranteeing every blob referenced by a newly
 *       published version has already been "seen live" by at least one completed GC
 *       enumeration before it could ever be collected;</li>
 *   <li>GC passes run inside {@link #runExclusive(Runnable)}: it waits until all outstanding
 *       read leases are released and blocks new leases for its duration. Enumeration of live
 *       references happens inside the exclusive section but the per-blob work is kept to cheap
 *       metadata scans + atomic renames, so reads/writes are never blocked for long.</li>
 * </ul>
 * The gate is per-JVM, matching the single-process deployment model; on-disk atomicity is
 * provided independently by the temp-file + atomic-rename protocol.
 */
@Component
public class ReadGate {

    private static final Logger log = LoggerFactory.getLogger(ReadGate.class);

    private final Object monitor = new Object();
    private int activeReadLeases;
    private boolean exclusive;

    public ReadGate() {
    }

    /** Acquire a shared read/publish lease (may block while a GC pass is exclusive). */
    public ReadLease acquireRead() {
        synchronized (monitor) {
            boolean interrupted = false;
            while (exclusive) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            activeReadLeases++;
            return new ReadLease();
        }
    }

    /** Run an action while holding exclusive access: waits until no read lease is active. */
    public void runExclusive(Runnable action) {
        synchronized (monitor) {
            boolean interrupted = false;
            while (exclusive || activeReadLeases > 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            exclusive = true;
        }
        try {
            action.run();
        } finally {
            synchronized (monitor) {
                exclusive = false;
                monitor.notifyAll();
            }
        }
    }

    private void releaseRead() {
        synchronized (monitor) {
            if (activeReadLeases > 0) {
                activeReadLeases--;
            }
            if (!exclusive && activeReadLeases == 0) {
                monitor.notifyAll();
            }
        }
    }

    /** Shared lease; close() releases exactly once and is idempotent, from any thread. */
    public final class ReadLease implements AutoCloseable {
        private volatile boolean released;

        private ReadLease() {
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                releaseRead();
            }
        }
    }
}
