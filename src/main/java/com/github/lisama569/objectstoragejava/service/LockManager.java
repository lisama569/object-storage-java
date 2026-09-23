package com.github.lisama569.objectstoragejava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-object striped locks.
 * <p>
 * Writers of the <em>same</em> object are serialised within this JVM (multipart
 * initialisation, part upload, completion and version publication); writers of different
 * objects never contend. The lock is only the in-process ordering primitive — visibility of
 * committed versions is additionally guaranteed by the atomic file operations in
 * {@code ObjectStorageService}.
 */
@Component
public class LockManager {

    private static final int STRIPE_COUNT = 256;

    private final Lock[] stripes;

    public LockManager() {
        this.stripes = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    /** Return the lock associated with an object name. */
    public Lock lockFor(String objectName) {
        int index = Math.floorMod(objectName.hashCode(), STRIPE_COUNT);
        return stripes[index];
    }
}
