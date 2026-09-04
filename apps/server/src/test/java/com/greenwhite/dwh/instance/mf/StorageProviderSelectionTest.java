package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.storage.LocalStorageProvider;
import com.greenwhite.dwh.instance.mf.storage.S3StorageConfiguration;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageProviderSelectionTest {

    @TempDir
    Path storagePath;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalStorageProvider.class, S3StorageConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void selectsExactlyOneLocalProviderByDefault() {
        runner.withPropertyValues("dwh.storage.local-path=" + storagePath)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StorageProvider.class))
                            .hasSize(1)
                            .allSatisfy((name, provider) -> assertThat(provider.getProviderCode())
                                    .isEqualTo("local_disk"));
                });
    }

    @Test
    void selectsExactlyOneS3ProviderWhenConfigured() {
        runner.withPropertyValues(
                        "dwh.providers.storage=s3",
                        "dwh.storage.s3.endpoint=http://127.0.0.1:19000",
                        "dwh.storage.s3.region=us-east-1",
                        "dwh.storage.s3.access-key=test-access",
                        "dwh.storage.s3.secret-key=test-secret",
                        "dwh.storage.s3.bucket=test-bucket")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StorageProvider.class))
                            .hasSize(1)
                            .allSatisfy((name, provider) -> assertThat(provider.getProviderCode())
                                    .isEqualTo("s3"));
                });
    }

    @Test
    void failsFastWhenS3SecretsAreMissing() {
        runner.withPropertyValues("dwh.providers.storage=s3")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasRootCauseMessage("DWH_S3_ENDPOINT must be an absolute HTTP(S) URI"));
    }
}
