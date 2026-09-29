package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.md.repository.MdI18nRepository;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MdI18nRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("dwh_i18n_repository_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdI18nRepository repository;
    static Long actorId;

    @BeforeAll
    static void setup() {
        var dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
                        """).query(Long.class).single();
    }

    @Test
    @DisplayName("Встроенные языки — русский, узбекский и английский; прежние встроенные выключены, но сохранены")
    void migrationSeedsSupportedLanguages() {
        assertThat(repository.findLanguages(true))
                .extracting(language -> language.code())
                .containsExactly("ru", "uz", "en");
        assertThat(repository.findLanguages(false))
                .filteredOn(language -> language.builtin())
                .extracting(language -> language.code())
                .containsExactly("ru", "uz", "en");
        assertThat(repository.findLanguages(false))
                .filteredOn(language -> !language.builtin())
                .allSatisfy(language -> assertThat(language.active()).isFalse())
                .extracting(language -> language.code())
                .containsExactlyInAnyOrder("kk", "ky", "tg", "de", "tr");
    }

    @Test
    @DisplayName("Пакет overrides заменяется атомарно и защищён ревизией")
    void replacesOverridesWithOptimisticLock() {
        repository.insertLanguage("fr", "Français", actorId);

        long revision = repository.replaceOverrides(
                "fr", Map.of("nav.tasks", "Tâches", "common.save", "Enregistrer"), 1L, actorId);

        assertThat(revision).isEqualTo(2L);
        assertThat(repository.findOverrides("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");
        assertThat(repository.findAllOverrides().get("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");

        assertThatThrownBy(() -> repository.replaceOverrides("fr", Map.of("nav.tasks", "Travail"), 1L, actorId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.i18n_revision_conflict");

        assertThat(repository.findOverrides("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Enregistrer");
    }

    @Test
    @DisplayName("Выключенный пользовательский язык включается снова; активный и встроенный — нет")
    void reactivatesOnlyASwitchedOffCustomLanguage() {
        try {
            var reactivated = repository.reactivateLanguage("kk", "Qazaq", actorId);
            assertThat(reactivated).hasValueSatisfying(language -> {
                assertThat(language.active()).isTrue();
                assertThat(language.name()).isEqualTo("Qazaq");
            });
            assertThat(repository.reactivateLanguage("kk", "Again", actorId)).isEmpty();
            assertThat(repository.reactivateLanguage("uz", "Uzbek", actorId)).isEmpty();
        } finally {
            // The seeding test expects every former built-in switched off.
            jdbc.sql("update md_i18n_languages set is_active = false where code = 'kk'")
                    .update();
        }
    }
}
