package com.greenwhite.dwh.instance.mf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartUploadConfigurationTest {

    private static final long MEBIBYTE = 1024L * 1024L;

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class));

    @Test
    @DisplayName("Active application config accepts a 50 MiB file plus bounded multipart overhead")
    void applicationConfigMatchesFileUploadContract() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            MultipartProperties properties = context.getBean(MultipartProperties.class);
            assertThat(properties.getMaxFileSize().toBytes()).isEqualTo(50L * MEBIBYTE);
            assertThat(properties.getMaxRequestSize().toBytes()).isEqualTo(51L * MEBIBYTE);
        });
    }
}
