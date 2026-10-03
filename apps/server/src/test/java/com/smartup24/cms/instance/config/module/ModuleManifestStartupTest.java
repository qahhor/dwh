package com.smartup24.cms.instance.config.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.module.ModuleManifestException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plan 10/10, item 6.4, acceptance "a module with minPlatform above the current one does not start, with a clear
 * error" (ADR-0033, 6.3): a module jar on the classpath whose manifest needs a newer platform API stops the context
 * before any bean, and the failure analysis names the module and both versions; a module that fits has its
 * configuration imported.
 */
class ModuleManifestStartupTest {

    @TempDir
    Path jar;

    /** The configuration of a module outside the monorepo, imported by the manifest. */
    @Configuration(proxyBeanMethods = false)
    static class ShelfModule {
        @Bean
        String shelfMarker() {
            return "shelf";
        }
    }

    private ClassLoader moduleOnClasspath(String json) throws IOException {
        Path manifest = jar.resolve("META-INF/smartupcms/modules/shelf.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, json, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void aModuleNeedingANewerPlatformStopsTheStartNamingIt() throws IOException {
        ClassLoader loader = moduleOnClasspath("""
                {"code": "shelf", "name": "Shelf", "version": "2.1.0", "minPlatform": "99.0.0",
                 "configuration": "%s"}
                """.formatted(ShelfModule.class.getName()));
        new ApplicationContextRunner()
                .withClassLoader(loader)
                .withUserConfiguration(ModuleConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    ModuleManifestException cause = causeOf(failure);
                    assertThat(cause.getMessage())
                            .contains("module shelf 2.1.0 needs platform API >= 99.0.0 (major 99)")
                            .contains("this platform provides 1.");
                    FailureAnalysis analysis = new ModuleManifestFailureAnalyzer().analyze(failure);
                    assertThat(analysis).isNotNull();
                    assertThat(analysis.getDescription()).contains("module shelf 2.1.0");
                    assertThat(analysis.getAction()).contains("minPlatform");
                });
    }

    @Test
    void aModuleMissingADependencyStopsTheStart() throws IOException {
        ClassLoader loader = moduleOnClasspath("""
                {"code": "shelf", "name": "Shelf", "version": "1.0.0", "minPlatform": "1.0.0",
                 "dependencies": [{"code": "library", "version": "1.0.0"}]}
                """);
        new ApplicationContextRunner()
                .withClassLoader(loader)
                .withUserConfiguration(ModuleConfiguration.class)
                .run(context -> assertThat(causeOf(context.getStartupFailure()).getMessage())
                        .contains("needs module library >= 1.0.0, which is not installed"));
    }

    @Test
    void aModuleThatFitsHasItsConfigurationImported() throws IOException {
        ClassLoader loader = moduleOnClasspath("""
                {"code": "shelf", "name": "Shelf", "version": "1.0.0", "minPlatform": "1.0.0",
                 "dependencies": [{"code": "iam", "version": "1.0.0"}], "configuration": "%s"}
                """.formatted(ShelfModule.class.getName()));
        new ApplicationContextRunner()
                .withClassLoader(loader)
                .withUserConfiguration(ModuleConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("shelfMarker")).isEqualTo("shelf");
                });
    }

    private static ModuleManifestException causeOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModuleManifestException refusal) return refusal;
        }
        throw new AssertionError("The start failed for another reason", failure);
    }
}
