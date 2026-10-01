package com.smartup24.cms.instance.fnd.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A connection timeout to pg-dwh is required: without {@code warehouse.connect-timeout} the start fails.
 * The context starts with {@link FndDwhConfig} only; the Hikari pool opens no connection at start
 * ({@code initializationFailTimeout = -1}), so no database is needed.
 */
class DwhDataSourcePropertiesTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:1/dwh_test_never_connected";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FndDwhConfig.class)
            .withPropertyValues("warehouse.url=" + URL, "warehouse.username=TEST", "warehouse.password=TEST");

    @Test
    @DisplayName("AC-36: без warehouse.connect-timeout контекст не стартует, причина называет таймаут")
    void missingTimeoutFailsStartup() {
        runner.run(context -> {
            assertThat(context.getStartupFailure())
                    .as("старт должен быть красным")
                    .isNotNull();
            assertThat(causeChain(context.getStartupFailure())).containsAnyOf("connect-timeout", "connectTimeout");
        });
    }

    @Test
    @DisplayName("AC-36: с таймаутом свойства связаны, пул pg-dwh создан")
    void timeoutBindsAndPoolIsCreated() {
        runner.withPropertyValues("warehouse.connect-timeout=2s").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(DwhDataSourceProperties.class).connectTimeout())
                    .isEqualTo(Duration.ofSeconds(2));
            assertThat(context.getBean("dwhDataSource", DataSource.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("AC-36: нулевой таймаут — старт красный")
    void zeroTimeoutFailsStartup() {
        runner.withPropertyValues("warehouse.connect-timeout=0s").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("должен быть положительным");
        });
    }

    private static String causeChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }
}
