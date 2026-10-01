package com.smartup24.cms.instance.fnd.config;

import com.smartup24.cms.instance.fnd.api.DwhUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * Connection to {@code pg-dwh} for maintenance jobs that scan the whole raw layer (cleanup, reconciliation).
 * The pool limits a statement by {@code warehouse.statement-timeout}; here the limit is raised to
 * {@code warehouse.maintenance-statement-timeout}, but only within a single transaction
 * ({@code set_config(..., true)}): the connection returns to the pool with the normal limit.
 */
public class FndDwhMaintenance {

    /** Work with a connection; its {@link SQLException} becomes {@link DwhUnavailableException}. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource dwh;
    private final String timeoutMs;

    public FndDwhMaintenance(DataSource dwh, Duration timeout) {
        this.dwh = dwh;
        this.timeoutMs = String.valueOf(timeout.toMillis());
    }

    /** Runs the work in one pg-dwh transaction with the maintenance job limit. */
    public <T> T inTransaction(Work<T> work) {
        try (Connection connection = dwh.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement limits =
                        connection.prepareStatement("select set_config('statement_timeout', ?, true),"
                                + " set_config('idle_in_transaction_session_timeout', ?, true)")) {
                    limits.setString(1, timeoutMs);
                    limits.setString(2, timeoutMs);
                    limits.execute();
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DwhUnavailableException(failure);
        }
    }
}
