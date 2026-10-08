package com.smartup24.cms.instance.config.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.health.ReadinessChecks;
import com.smartup24.cms.instance.mf.api.FileScannerProbe;
import com.smartup24.cms.instance.mf.scan.ClamAvFileScanner;
import com.smartup24.cms.instance.search.api.TypesenseProperties;
import com.smartup24.cms.instance.support.TestDatabases;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Plan 10/10, item 0.7: each readiness member reports a dead dependency as DOWN, and fast. */
class ReadinessHealthTest {

    private static final Duration DEADLINE = Duration.ofMillis(500);
    private final ReadinessHealthConfiguration config = new ReadinessHealthConfiguration(DEADLINE);

    @Test
    @DisplayName("A check that hangs answers DOWN at the deadline")
    void hangingCheckIsDownAtTheDeadline() {
        long start = System.nanoTime();
        Health health = ReadinessChecks.within(DEADLINE, () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Health.up().build();
        });

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Database: UP on a live database, DOWN on an unreachable one")
    void database() {
        JdbcClient live = JdbcClient.create(TestDatabases.migratedCopy("smc_readiness"));
        JdbcClient dead =
                JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "none", ""));

        assertThat(config.databaseHealthIndicator(live).health().getStatus()).isEqualTo(Status.UP);
        assertThat(config.databaseHealthIndicator(dead).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Typesense: switched off is UP, enabled and unreachable is DOWN")
    void typesense() {
        var off = new TypesenseProperties("http://127.0.0.1:1", "key", false, false);
        var unreachable = new TypesenseProperties("http://127.0.0.1:1", "key", true, false);

        assertThat(config.typesenseHealthIndicator(provider(off)).health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(config.typesenseHealthIndicator(provider(unreachable))
                        .health()
                        .getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("ClamAV: not required is UP; required is DOWN without the scanner and UP when it answers PONG")
    void clamav() throws Exception {
        assertThat(clamav(null, false).health().getStatus()).isEqualTo(Status.UP);
        assertThat(clamav(null, true).health().getStatus()).isEqualTo(Status.DOWN);

        try (ServerSocket daemon = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> answerPong(daemon));
            var scanner = new ClamAvFileScanner(
                    new SimpleMeterRegistry(),
                    "127.0.0.1",
                    daemon.getLocalPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));

            assertThat(clamav(scanner, true).health().getStatus()).isEqualTo(Status.UP);
        }
    }

    private HealthIndicator clamav(ClamAvFileScanner scanner, boolean required) {
        return config.clamavHealthIndicator(provider((FileScannerProbe) scanner), required);
    }

    private static void answerPong(ServerSocket daemon) {
        try (Socket client = daemon.accept()) {
            InputStream in = client.getInputStream();
            byte[] command = new byte["zPING\0".length()];
            in.readNBytes(command, 0, command.length);
            OutputStream out = client.getOutputStream();
            out.write("PONG\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Exception ignored) {
            // the test fails on the missing PONG
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
