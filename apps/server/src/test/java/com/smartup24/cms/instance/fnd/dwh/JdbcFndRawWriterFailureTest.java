package com.smartup24.cms.instance.fnd.dwh;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.fnd.api.DwhUnavailableException;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** pg-dwh away: counting and reading raw rows fail with the fnd error for an unavailable warehouse, not a SQL trace. */
class JdbcFndRawWriterFailureTest {

    @Test
    void unavailableWarehouseIsReportedAsSuch() throws SQLException {
        DataSource dwh = mock(DataSource.class);
        when(dwh.getConnection()).thenThrow(new SQLException("connection refused"));
        var writer = new JdbcFndRawWriter(
                dwh, mock(JdbcClient.class), mock(PlatformTransactionManager.class), new ObjectMapper());

        assertThatThrownBy(() -> writer.count(1)).isInstanceOf(DwhUnavailableException.class);
        assertThatThrownBy(() -> writer.read(1)).isInstanceOf(DwhUnavailableException.class);
    }
}
