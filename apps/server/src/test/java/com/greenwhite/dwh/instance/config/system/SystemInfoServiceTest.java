package com.greenwhite.dwh.instance.config.system;

import com.greenwhite.dwh.instance.common.provider.ProviderRegistry;
import com.greenwhite.dwh.instance.config.bootstrap.InstanceBootstrapProperties;
import com.greenwhite.dwh.instance.search.typesense.TypesenseProperties;
import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                jdbc, providers, backup, typesense, bootstrap, buildProperties,
                Duration.ofMillis(50), Duration.ZERO);
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
        when(backup.read()).thenReturn(new BackupStatus(
                "SUCCESS", Instant.now().minus(Duration.ofDays(2)), null));
        TypesenseProperties typesense = new TypesenseProperties("http://typesense:8108", "test-key", false, false);
        InstanceBootstrapProperties bootstrap = mock(InstanceBootstrapProperties.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        when(buildProperties.getIfAvailable()).thenReturn(null);

        SystemInfoService service = new SystemInfoService(
                jdbc, providers, backup, typesense, bootstrap, buildProperties,
                Duration.ofMillis(50), Duration.ofHours(24));
        try {
            BackupStatus status = service.getInfo().backup();

            assertThat(status.freshness()).isEqualTo("STALE");
            assertThat(status.maxAgeSeconds()).isEqualTo(86_400L);
        } finally {
            service.close();
        }
    }
}
