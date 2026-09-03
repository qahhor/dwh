package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.service.MfFileObjectLock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MfFileObjectLockTest {

    @Test
    void serializesLifecycleChangesForTheSameContentHash() throws Exception {
        MfFileObjectLock locks = new MfFileObjectLock();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> locks.withLock("same-sha", () -> {
                firstEntered.countDown();
                await(releaseFirst);
            }));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> {
                secondStarted.countDown();
                locks.withLock("same-sha", secondEntered::countDown);
            });
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS))
                    .as("the second lifecycle change must wait for the first")
                    .isFalse();

            releaseFirst.countDown();
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while coordinating lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating lock test", exception);
        }
    }
}
