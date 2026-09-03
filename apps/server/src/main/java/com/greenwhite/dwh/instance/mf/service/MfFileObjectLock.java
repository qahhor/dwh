package com.greenwhite.dwh.instance.mf.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes publish/delete operations for one content hash in the supported
 * single-server topology. Stripes keep memory bounded independently of the
 * number of uploaded files.
 */
@Component
public class MfFileObjectLock {

    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public MfFileObjectLock() {
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    public <T> T withLock(String contentHash, Supplier<T> action) {
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(action, "action");
        ReentrantLock lock = stripes[Math.floorMod(contentHash.hashCode(), stripes.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(String contentHash, Runnable action) {
        withLock(contentHash, () -> {
            action.run();
            return null;
        });
    }
}
