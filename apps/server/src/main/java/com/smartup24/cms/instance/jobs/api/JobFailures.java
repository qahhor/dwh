package com.smartup24.cms.instance.jobs.api;

import com.smartup24.cms.instance.common.error.TransientFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Tells a transient failure from a final one for job handlers (plan 10/10, item 3.8): pg-dwh or the OLTP database
 * away, a storage read that broke off. A module marks its own such exception with {@link TransientFailure}, so the
 * queue does not depend on the module (plan 10/10, item 4.2). Such a failure is worth the runner's retry; anything else (a bug, a file that
 * does not match its format) fails the same way on every attempt.
 */
public final class JobFailures {

    /** A cause chain longer than this is cut: it guards against a chain that loops back on itself. */
    private static final int MAX_DEPTH = 32;

    private JobFailures() {}

    /** True when the failure or any of its causes is one a later attempt may not meet. */
    public static boolean isTransient(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof TransientFailure
                    || cause instanceof IOException
                    || cause instanceof UncheckedIOException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || (cause instanceof SdkException sdk && sdk.retryable())) {
                return true;
            }
        }
        return false;
    }
}
