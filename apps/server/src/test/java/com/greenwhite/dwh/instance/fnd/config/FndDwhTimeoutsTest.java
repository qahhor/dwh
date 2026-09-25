package com.greenwhite.dwh.instance.fnd.config;

import com.greenwhite.dwh.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0 DWH: пул pg-dwh не бывает «без таймаута». Сервер обрывает долгий запрос и брошенную транзакцию
 * по {@code app.dwh.statement-timeout}; задания обслуживания получают свой предел только внутри
 * транзакции и возвращают соединение в пул с обычным.
 */
class FndDwhTimeoutsTest {

    private HikariDataSource dwh;

    @BeforeEach
    void pool() {
        DwhDataSourceProperties props = new DwhDataSourceProperties(TestDatabases.jdbcUrl(TestDatabases.DWH_DB),
                TestDatabases.USER, "", Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(5));
        dwh = (HikariDataSource) new FndDwhConfig().dwhDataSource(props);
        // Одно соединение: задание обслуживания и следующий запрос получают одно и то же
        dwh.setMaximumPoolSize(1);
    }

    @AfterEach
    void close() {
        dwh.close();
    }

    @Test
    @DisplayName("P0: запрос дольше statement-timeout обрывается сервером")
    void longStatementIsCancelled() throws SQLException {
        try (Connection connection = dwh.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(show(statement, "statement_timeout")).isEqualTo("1s");
            assertThat(show(statement, "idle_in_transaction_session_timeout")).isEqualTo("1s");
            assertThatThrownBy(() -> statement.execute("select pg_sleep(1.5)"))
                    .isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("57014"));
        }
    }

    @Test
    @DisplayName("P0: брошенная открытой транзакция закрывается сервером, соединение не занято навсегда")
    void idleTransactionIsTerminated() throws Exception {
        try (Connection connection = dwh.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select 1");
            }
            Thread.sleep(1500);
            assertThatThrownBy(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("select 1");
                }
            }).isInstanceOf(SQLException.class);
        }
        try (Connection connection = dwh.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(show(statement, "server_version")).isNotBlank();
        }
    }

    @Test
    @DisplayName("P0: задание обслуживания работает дольше обычного предела, пул получает соединение с обычным")
    void maintenanceHasItsOwnLimitInsideTheTransaction() throws SQLException {
        FndDwhMaintenance maintenance = new FndDwhMaintenance(dwh, Duration.ofSeconds(5));

        String inside = maintenance.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("select pg_sleep(1.5)");
                return show(statement, "statement_timeout");
            }
        });

        assertThat(inside).isEqualTo("5s");
        try (Connection connection = dwh.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(show(statement, "statement_timeout")).isEqualTo("1s");
        }
    }

    private static String show(Statement statement, String setting) throws SQLException {
        try (ResultSet rs = statement.executeQuery("show " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
