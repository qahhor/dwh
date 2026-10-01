package com.smartup24.cms.instance.common.jdbc;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A time limit on each statement of a piece of work, held by the database itself ({@code statement_timeout}, local
 * to the transaction). {@code @Transactional(timeout = …)} holds only when the method opens the transaction: a
 * request with an Idempotency-Key runs inside the filter's transaction, where the inner timeout is ignored. This
 * limit holds either way, and the previous value is restored after the work, so the rest of an outer transaction
 * keeps its own limit.
 */
public final class StatementTimeouts {

    private StatementTimeouts() {}

    /** Runs {@code work} with every statement limited to {@code limit}; a statement that runs longer fails. */
    public static <T> T within(JdbcClient jdbc, Duration limit, Supplier<T> work) {
        String previous = jdbc.sql("select current_setting('statement_timeout')")
                .query(String.class)
                .single();
        set(jdbc, limit.toMillis() + "ms");
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error failure) {
            restoreAfter(jdbc, previous, failure);
            throw failure;
        }
        set(jdbc, previous);
        return result;
    }

    /** {@link #within(JdbcClient, Duration, Supplier)} for work without a result. */
    public static void run(JdbcClient jdbc, Duration limit, Runnable work) {
        within(jdbc, limit, () -> {
            work.run();
            return Boolean.TRUE;
        });
    }

    private static void set(JdbcClient jdbc, String value) {
        jdbc.sql("select set_config('statement_timeout', :value, true)")
                .param("value", value)
                .query(String.class)
                .single();
    }

    /** After a failed statement the transaction accepts no more statements; the failure stays the one reported. */
    private static void restoreAfter(JdbcClient jdbc, String previous, Throwable failure) {
        try {
            set(jdbc, previous);
        } catch (RuntimeException restoreFailed) {
            failure.addSuppressed(restoreFailed);
        }
    }
}
