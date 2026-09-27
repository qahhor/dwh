package com.smartup24.cms.instance.common.health;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;

/**
 * Runs a readiness check under a hard deadline (plan 10/10, item 0.7).
 *
 * <p>A stopped database does not fail fast: the pool waits its connection timeout, 20 seconds for the main one. A
 * probe that hangs that long reads as a timeout to the container healthcheck and says nothing; under the deadline it
 * answers DOWN at once, and the readiness group turns 503.
 */
public final class ReadinessChecks {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private ReadinessChecks() {}

    public static Health within(Duration deadline, Supplier<Health> check) {
        Future<Health> result = EXECUTOR.submit(check::get);
        try {
            return result.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result.cancel(true);
            return Health.down()
                    .withDetail("reason", "timeout " + deadline.toMillis() + " ms")
                    .build();
        } catch (ExecutionException e) {
            return Health.down()
                    .withDetail(
                            "reason",
                            Objects.requireNonNullElse(e.getCause(), e)
                                    .getClass()
                                    .getSimpleName())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "interrupted").build();
        }
    }
}
