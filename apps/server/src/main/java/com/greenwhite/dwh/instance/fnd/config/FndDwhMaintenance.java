package com.greenwhite.dwh.instance.fnd.config;

import com.greenwhite.dwh.instance.fnd.dwh.DwhUnavailableException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Соединение с {@code pg-dwh} для заданий обслуживания, которые проходят весь raw (очистка, сверка).
 * Пул ограничивает запрос {@code app.dwh.statement-timeout}; здесь предел поднимается до
 * {@code app.dwh.maintenance-statement-timeout}, но только внутри одной транзакции
 * ({@code set_config(..., true)}): в пул соединение возвращается с обычным пределом.
 */
public class FndDwhMaintenance {

    /** Работа с соединением; её {@link SQLException} становится {@link DwhUnavailableException}. */
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

    /** Выполняет работу в одной транзакции pg-dwh с пределом заданий обслуживания. */
    public <T> T inTransaction(Work<T> work) {
        try (Connection connection = dwh.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement limits = connection.prepareStatement(
                        "select set_config('statement_timeout', ?, true),"
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
