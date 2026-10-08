package com.smartup24.cms.instance.config.observability;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A data source whose connections trace their statements ({@link JdbcTracing}). A {@link DelegatingDataSource}, so
 * pool metrics and health still find the pool behind it; closing it closes the pool.
 */
final class TracingDataSource extends DelegatingDataSource implements AutoCloseable {

    private final JdbcTracing tracing;

    TracingDataSource(DataSource target, JdbcTracing tracing) {
        super(target);
        this.tracing = tracing;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return tracing.connection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return tracing.connection(super.getConnection(username, password));
    }

    @Override
    public void close() throws Exception {
        if (obtainTargetDataSource() instanceof AutoCloseable pool) {
            pool.close();
        }
    }
}
