package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdI18nRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MdI18nRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_i18n_repository_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdI18nRepository repository;
    static Long actorId;

    @BeforeAll
    static void setup() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new MdI18nRepository(jdbc, new ObjectMapper());
        actorId = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, language, timezone)
                        values ('I18n Admin', 'i18n_admin', 'i18n-admin@test.local', 'x', 'ru', 'UTC')
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("Миграция регистрирует восемь встроенных языков")
    void migrationSeedsSupportedLanguages() {
        assertThat(repository.findLanguages(false))
                .extracting(language -> language.code())
                .containsExactly("ru", "uz", "en", "kk", "ky", "tg", "de", "tr");
        assertThat(repository.findLanguages(false))
                .allSatisfy(language -> {
                    assertThat(language.builtin()).isTrue();
                    assertThat(language.active()).isTrue();
                    assertThat(language.revision()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("Пакет overrides заменяется атомарно и защищён ревизией")
    void replacesOverridesWithOptimisticLock() {
        repository.insertLanguage("fr", "Français", actorId);

        long revision = repository.replaceOverrides(
                "fr",
                Map.of("nav.tasks", "Tâches", "common.save", "Enregistrer"),
                1L,
                actorId);

        assertThat(revision).isEqualTo(2L);
        assertThat(repository.findOverrides("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");
        assertThat(repository.findAllOverrides().get("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");

        assertThatThrownBy(() -> repository.replaceOverrides(
                "fr", Map.of("nav.tasks", "Travail"), 1L, actorId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("изменён другим администратором");

        assertThat(repository.findOverrides("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");
    }
}
