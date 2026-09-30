package com.smartup24.cms.instance.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Plan 10/10, item 3.13 (ADR-0025): how a notice leaves the node. */
class CacheInvalidationsTest {

    @Test
    @DisplayName("3.13: inside a transaction the notice is queued on its own connection, delivered by the commit")
    void insideATransactionTheNoticeGoesWithTheTransaction() {
        DataSource dataSource = mock(DataSource.class);
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        CacheInvalidations invalidations = new CacheInvalidations(dataSource, jdbc);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(mock(Connection.class)));
        try {
            invalidations.publish("probe");

            verify(jdbc).sql("select pg_notify(:channel, :payload)");
            // Nothing is left for after the commit, when the connection belongs to a finished transaction.
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("3.13: outside a transaction the notice goes at once on a connection of its own")
    void outsideATransactionTheNoticeGoesOnItsOwnConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        JdbcClient jdbc = mock(JdbcClient.class);
        CacheInvalidations invalidations = new CacheInvalidations(dataSource, jdbc);

        invalidations.publish("probe");

        verify(statement).setString(1, CacheInvalidations.CHANNEL);
        verify(statement).setString(eq(2), endsWith(" probe"));
        verify(statement).execute();
        verify(connection).close();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("3.13: a synchronized scope without a transaction of this data source sends after the commit")
    void foreignTransactionSendsAfterCommit() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        JdbcClient jdbc = mock(JdbcClient.class);
        CacheInvalidations invalidations = new CacheInvalidations(dataSource, jdbc);
        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidations.publish("probe");

            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(dataSource, never()).getConnection();
            verifyNoInteractions(jdbc);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("3.13: a failed notice outside a transaction is logged; the change stands")
    void failedNoticeIsLogged() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("database is down"));
        CacheInvalidations invalidations = new CacheInvalidations(dataSource, mock(JdbcClient.class));

        invalidations.publish("probe");

        verify(dataSource).getConnection();
    }

    @Test
    @DisplayName("3.13: without a database nothing is sent and nothing fails")
    void withoutDatabase() {
        JdbcClient jdbc = mock(JdbcClient.class);
        new CacheInvalidations(null, jdbc).publish("probe");
        verifyNoInteractions(jdbc);
    }
}
