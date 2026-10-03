package com.github.lisama569.objectstoragejava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JVM-global read/write gate coordinating object readers with blob garbage collection.
 * Skeleton: locking strategy is implemented together with the read pipeline.
 */
@Component
public class ReadGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public ReadGate() {
    }

    /** Run an action while holding the exclusive (GC) lock. */
    public void runExclusive(Runnable action) {
    }
}
