package com.smartup24.cms.instance.config.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 10/10, item 3.8: the main pool is never "without a timeout". Every connection starts with the statement and the
 * idle-in-transaction limits of {@code smc.database.*} (60 s by default), set by the server from the JDBC options.
 */
class OltpTimeoutsTest extends EmbeddedPostgresTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("connections of the main pool carry the default statement and idle-in-transaction limits")
    void poolConnectionsCarryTheLimits() {
        assertThat(environment.getProperty("smc.database.statement-timeout")).isEqualTo("60s");
        assertThat(environment.getProperty("smc.database.idle-in-transaction-timeout"))
                .isEqualTo("60s");
        // Inside a transaction the same pooled connection answers both
        String limits = tx.execute(status -> jdbc.sql("select current_setting('statement_timeout') || ' ' ||"
                        + " current_setting('idle_in_transaction_session_timeout')")
                .query(String.class)
                .single());
        assertThat(limits).isEqualTo("1min 1min");
        assertThat(jdbc.sql("select source from pg_settings where name = 'statement_timeout'")
                        .query(String.class)
                        .single())
                .as("set by the client's startup options, not by the server's configuration")
                .isEqualTo("client");
    }
}
