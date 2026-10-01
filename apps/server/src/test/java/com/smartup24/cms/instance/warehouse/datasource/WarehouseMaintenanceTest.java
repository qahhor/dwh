package com.smartup24.cms.instance.warehouse.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.error.TransientFailure;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The maintenance transaction of pg-dwh without a database: it commits the work, rolls back a failed one, and reports
 * a database that does not answer as the warehouse being unavailable, a failure the job queue retries (plan 10/10,
 * items 3.8 and 4.2).
 */
class WarehouseMaintenanceTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement limits = mock(PreparedStatement.class);
    private final WarehouseMaintenance maintenance = new WarehouseMaintenance(dataSource, Duration.ofMinutes(30));

    private void connects() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(limits);
    }

    @Test
    @DisplayName("4.2: the work runs under the maintenance limit and commits")
    void workCommits() throws SQLException {
        connects();

        Integer result = maintenance.inTransaction(c -> 7);
        assertThat(result).isEqualTo(7);

        verify(connection).setAutoCommit(false);
        verify(limits).setString(1, "1800000");
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("4.2: a failed statement rolls back and is the warehouse being unavailable, a transient failure")
    void failedWorkRollsBack() throws SQLException {
        connects();

        assertThatThrownBy(() -> maintenance.inTransaction(c -> {
                    throw new SQLException("canceling statement due to statement timeout");
                }))
                .isInstanceOf(WarehouseUnavailableException.class)
                .isInstanceOf(TransientFailure.class)
                .satisfies(e -> assertThat(((WarehouseUnavailableException) e).code())
                        .isEqualTo(WarehouseError.DWH_UNAVAILABLE));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("4.2: a failure of the work itself rolls back and reaches the caller as it is")
    void runtimeFailureRollsBack() throws SQLException {
        connects();

        assertThatThrownBy(() -> maintenance.inTransaction(c -> {
                    throw new IllegalStateException("TEST");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEST");
        verify(connection).rollback();
    }

    @Test
    @DisplayName("4.2: no connection is the warehouse being unavailable")
    void noConnection() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        assertThatThrownBy(() -> maintenance.inTransaction(c -> 1))
                .isInstanceOf(WarehouseUnavailableException.class)
                .hasCauseInstanceOf(SQLException.class);
    }
}
