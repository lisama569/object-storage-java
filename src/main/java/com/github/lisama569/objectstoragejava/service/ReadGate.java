package com.github.lisama569.objectstoragejava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Coordinates object readers/publishers with blob garbage collection.
 * <p>
 * Unlike {@link java.util.concurrent.locks.ReentrantReadWriteLock}, read permits are
 * <b>not thread-bound</b>: a verified byte stream acquires its permit on the request thread and
 * releases it when the stream is closed (which, under async MVC streaming, happens on a different
 * thread). Semantics are otherwise the classic reader/writer gate:
 * <ul>
 *   <li>any number of readers (and blob-materialising publications) may hold permits at once;</li>
 *   <li>garbage collection holds exclusivity: once it starts waiting, no new permit is granted
 *       until the GC pass finishes, so GC is never starved by continuous traffic, and no blob
 *       that is open by a reader can be quarantined or deleted.</li>
 * </ul>
 */
@Component
public class ReadGate {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition drained = lock.newCondition();
    private final AtomicInteger activeReaders = new AtomicInteger();
    private boolean exclusive;

    public ReadGate() {
    }

    /**
     * Acquire one read permit; MUST be balanced by {@link #releaseRead()} (normally in the
     * verified byte stream's {@code close()}, possibly on another thread).
     */
    public void acquireRead() {
        lock.lock();
        try {
            while (exclusive) {
                drained.awaitUninterruptibly();
            }
            activeReaders.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /** Release one read permit previously acquired with {@link #acquireRead()}. */
    public void releaseRead() {
        lock.lock();
        try {
            int remaining = activeReaders.decrementAndGet();
            if (remaining < 0) {
                // Defensive: an unmatched release must never corrupt the gate state.
                activeReaders.incrementAndGet();
                throw new IllegalMonitorStateException("attempt to release a read permit that was not held");
            }
            if (remaining == 0) {
                drained.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Acquire a read permit, run the action and release the permit again. */
    public <T> T runWithReadLock(Supplier<T> action) {
        acquireRead();
        try {
            return action.get();
        } finally {
            releaseRead();
        }
    }

    /** Acquire a read permit around a side-effecting action. */
    public void runWithReadLock(Runnable action) {
        runWithReadLock(() -> {
            action.run();
            return null;
        });
    }

    /** Run an action with exclusivity: waits until all previously granted permits are released. */
    public <T> T runExclusive(Supplier<T> action) {
        lock.lock();
        try {
            exclusive = true;
            boolean interrupted = false;
            while (activeReaders.get() > 0) {
                drained.awaitUninterruptibly();
            }
            try {
                return action.get();
            } finally {
                exclusive = false;
                drained.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Run an action while holding exclusivity. */
    public void runExclusive(Runnable action) {
        runExclusive(() -> {
            action.run();
            return null;
        });
    }
}
