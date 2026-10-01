package com.smartup24.cms.instance.config.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrapProperties;
import com.smartup24.cms.instance.search.typesense.TypesenseProperties;
import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;

class SystemInfoServiceTest {

    @Test
    void returnsDegradedSnapshotWithinTheHealthDeadlineWhenStorageProbeHangs() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("s3");
        when(storage.checkHealth()).thenAnswer(ignored -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ProviderHealth.unhealthy("s3", "late result", 10_000);
        });
        when(providers.getActiveStorageProvider()).thenReturn(storage);

        BackupStatusReader backup = mock(BackupStatusReader.class);
        when(backup.read()).thenReturn(new BackupStatus("NEVER", null, null));
        TypesenseProperties typesense = new TypesenseProperties("http://typesense:8108", "test-key", false, false);
        InstanceBootstrapProperties bootstrap = mock(InstanceBootstrapProperties.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        when(buildProperties.getIfAvailable()).thenReturn(null);

        SystemInfoService service = new SystemInfoService(
                new SystemInfoRepository(jdbc),
                providers,
                backup,
                typesense,
                bootstrap,
                buildProperties,
                Duration.ofMillis(50),
                Duration.ZERO);
        try {
            long startedAt = System.nanoTime();
            SystemInfoResponse response = service.getInfo();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(response.components().get("storage").status()).isEqualTo("DEGRADED");
            assertThat(elapsedMillis).isLessThan(1_000);
        } finally {
            service.close();
        }
    }

    @Test
    void evaluatesBackupFreshnessBeforeReturningTheSystemSnapshot() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        BackupStatusReader backup = mock(BackupStatusReader.class);
        when(backup.read()).thenReturn(new BackupStatus("SUCCESS", Instant.now().minus(Duration.ofDays(2)), null));
        TypesenseProperties typesense = new TypesenseProperties("http://typesense:8108", "test-key", false, false);
        InstanceBootstrapProperties bootstrap = mock(InstanceBootstrapProperties.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        when(buildProperties.getIfAvailable()).thenReturn(null);

        SystemInfoService service = new SystemInfoService(
                new SystemInfoRepository(jdbc),
                providers,
                backup,
                typesense,
                bootstrap,
                buildProperties,
                Duration.ofMillis(50),
                Duration.ofHours(24));
        try {
            BackupStatus status = service.getInfo().backup();

            assertThat(status.freshness()).isEqualTo("STALE");
            assertThat(status.maxAgeSeconds()).isEqualTo(86_400L);
        } finally {
            service.close();
        }
    }

    @Test
    void reportsTypesenseByItsHealthAnswerAndStorageByItsProbe() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/up/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/busy/health", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThat(snapshot(base + "/up/", ProviderHealth.healthy("local", 1)))
                    .containsEntry("typesense", "UP")
                    .containsEntry("storage", "UP");
            assertThat(snapshot(base + "/busy", null))
                    .containsEntry("typesense", "DEGRADED")
                    .containsEntry("storage", "UNKNOWN");
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> snapshot(String typesenseUrl, ProviderHealth storageHealth) {
        ProviderRegistry providers = mock(ProviderRegistry.class);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("local");
        when(storage.checkHealth()).thenReturn(storageHealth);
        when(providers.getActiveStorageProvider()).thenReturn(storage);
        BackupStatusReader backup = mock(BackupStatusReader.class);
        when(backup.read()).thenReturn(new BackupStatus("NEVER", null, null));
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        SystemInfoService service = new SystemInfoService(
                new SystemInfoRepository(mock(JdbcClient.class)),
                providers,
                backup,
                new TypesenseProperties(typesenseUrl, "test-key", true, false),
                mock(InstanceBootstrapProperties.class),
                buildProperties,
                Duration.ofSeconds(3),
                Duration.ZERO);
        try {
            Map<String, String> statuses = new LinkedHashMap<>();
            service.getInfo().components().forEach((name, component) -> statuses.put(name, component.status()));
            return statuses;
        } finally {
            service.close();
        }
    }
}
