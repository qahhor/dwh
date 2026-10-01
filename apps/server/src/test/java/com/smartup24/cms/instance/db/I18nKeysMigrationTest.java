package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 4.5 (ADR-0031): V156 moves administrators' translation overrides and menu title keys from the
 * transliterated keys to the semantic ones, and no value is lost. A database stops before V156, gets overrides on
 * the old keys, and is then migrated to the end.
 */
class I18nKeysMigrationTest {

    private static final String DATABASE = "i18n_keys_upgrade";
    private static final Path MAPPING = Path.of("../web/scripts/i18n-key-renames.json");
    private static final Pattern MAPPING_ROW = Pattern.compile("\\['([a-z0-9_.]+)', '([a-z0-9_.]+)'\\]");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    /** Overrides on old keys: language, old key, the administrator's value. */
    private static final List<List<String>> LEGACY_OVERRIDES = List.of(
            List.of("ru", "tasks.soispolniteli", "Соисполнители задачи"),
            List.of("uz", "tasks.soispolniteli", "Hamijrochilar"),
            List.of("en", "tasks.soispolniteli", "Co-assignees"),
            List.of("ru", "iam.telefon.822f9fd", "Мобильный телефон"),
            List.of("ru", "iam.telefon", "Мобильный:"),
            List.of("en", "ui.markdown_editor.kursiv.64bef33", "slanted"),
            List.of("uz", "analytics.raspredelenie_aktivnyh_i_vypolnennyh_zadach_po_i", "Ijrochilar kesimida"));

    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static Map<String, String> mapping;
    private static Map<String, Long> revisionsBefore;
    private static long menuRevisionBefore;
    private static long untouchedMenuRevisionBefore;

    @BeforeAll
    static void migrateWithLegacyOverrides() throws IOException {
        mapping = new ObjectMapper().readValue(MAPPING.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
        TestDatabases.createDatabase(DATABASE);
        dataSource = TestDatabases.pooled(TestDatabases.jdbcUrl(DATABASE), TestDatabases.USER, null, 2, DATABASE);
        jdbc = JdbcClient.create(dataSource);
        flyway(lastVersionBefore(156)).migrate();

        for (List<String> row : LEGACY_OVERRIDES) {
            override(row.get(0), row.get(1), row.get(2));
        }
        // Both keys overridden: the value under the new key stays.
        override("ru", "analytics.eksport", "Выгрузка (старый ключ)");
        override("ru", "analytics.dashboard.export", "Выгрузка (новый ключ)");
        // Keys that are not renamed, and a language without renamed overrides.
        override("ru", "common.save", "Сохранить!");
        override("kk", "common.save", "Сақтау");
        jdbc.sql("""
                insert into md_navigation_items (code, title, title_key, url) values
                    ('legacy-title', 'Analytics', 'layout.app_shell.analitika', '/analytics'),
                    ('current-title', 'Tasks', 'nav.tasks', '/tasks')
                """).update();
        revisionsBefore = languageRevisions();
        menuRevisionBefore = menuRevision("legacy-title");
        untouchedMenuRevisionBefore = menuRevision("current-title");

        flyway(null).migrate();
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @Test
    @DisplayName("4.5: every administrator's override keeps its value under the new key")
    void overridesMoveToTheNewKeys() {
        for (List<String> row : LEGACY_OVERRIDES) {
            String target = mapping.get(row.get(1));
            assertThat(target).as(row.get(1)).isNotNull();
            assertThat(overrideValue(row.get(0), target))
                    .as(row.get(0) + ":" + target)
                    .isEqualTo(row.get(2));
        }
        assertThat(overrideValue("ru", "iam.common.phone")).isEqualTo("Мобильный телефон");
        assertThat(overrideValue("ru", "iam.common.phone_label")).isEqualTo("Мобильный:");
        assertThat(overrideValue("ru", "common.save")).isEqualTo("Сохранить!");
        assertThat(overrideValue("kk", "common.save")).isEqualTo("Сақтау");
    }

    @Test
    @DisplayName("4.5: an override under both keys keeps the value under the new key, the old row is gone")
    void bothKeysKeepTheNewValue() {
        assertThat(overrideValue("ru", "analytics.dashboard.export")).isEqualTo("Выгрузка (новый ключ)");
        assertThat(overrideValue("ru", "analytics.eksport")).isNull();
    }

    @Test
    @DisplayName("4.5: no override and no menu item keeps an old key")
    void noOldKeyIsLeft() {
        assertThat(jdbc.sql("select translation_key from md_i18n_translation_overrides")
                        .query(String.class)
                        .list())
                .doesNotContainAnyElementsOf(mapping.keySet());
        assertThat(jdbc.sql("select title_key from md_navigation_items where title_key is not null")
                        .query(String.class)
                        .list())
                .doesNotContainAnyElementsOf(mapping.keySet())
                .contains("layout.app_shell.analytics", "nav.tasks");
    }

    @Test
    @DisplayName("4.5: languages and menu items that changed get a new revision, the others keep theirs")
    void revisionsMoveOnlyWhereSomethingChanged() {
        Map<String, Long> after = languageRevisions();
        for (String language : List.of("ru", "uz", "en")) {
            assertThat(after.get(language)).as(language).isEqualTo(revisionsBefore.get(language) + 1);
        }
        assertThat(after.get("kk")).isEqualTo(revisionsBefore.get("kk"));
        assertThat(menuRevision("legacy-title")).isEqualTo(menuRevisionBefore + 1);
        assertThat(menuRevision("current-title")).isEqualTo(untouchedMenuRevisionBefore);
    }

    @Test
    @DisplayName("4.5: the migration renames exactly the pairs of the committed mapping")
    void migrationAndMappingAgree() throws IOException {
        String sql = new ClassPathResource("db/migration/V156__i18n_semantic_keys.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> migrated = new LinkedHashMap<>();
        Matcher row = MAPPING_ROW.matcher(sql);
        while (row.find()) {
            migrated.put(row.group(1), row.group(2));
        }
        assertThat(migrated).isEqualTo(mapping);
    }

    /** The newest migration below {@code version}, whatever lands between the releases. */
    private static String lastVersionBefore(int version) throws IOException {
        int last = 0;
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql")) {
            Matcher name = VERSION.matcher(String.valueOf(file.getFilename()));
            if (name.matches() && Integer.parseInt(name.group(1)) < version) {
                last = Math.max(last, Integer.parseInt(name.group(1)));
            }
        }
        return String.valueOf(last);
    }

    private static Flyway flyway(String target) {
        var configuration = FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void override(String language, String key, String value) {
        jdbc.sql("""
                        insert into md_i18n_translation_overrides (language_code, translation_key, value)
                        values (:language, :key, :value)
                        """)
                .param("language", language)
                .param("key", key)
                .param("value", value)
                .update();
    }

    private static String overrideValue(String language, String key) {
        return jdbc.sql("""
                        select value from md_i18n_translation_overrides
                        where language_code = :language and translation_key = :key
                        """)
                .param("language", language)
                .param("key", key)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static Map<String, Long> languageRevisions() {
        Map<String, Long> revisions = new LinkedHashMap<>();
        jdbc.sql("select code, revision from md_i18n_languages")
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(entry -> revisions.put(entry.getKey(), entry.getValue()));
        return revisions;
    }

    private static long menuRevision(String code) {
        return jdbc.sql("select revision from md_navigation_items where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }
}
