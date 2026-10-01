package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 4.4 (ADR-0028): V147 moves granted rights from the old form codes to the new ones, and every user
 * keeps the same effective rights. A database stops at V146, gets roles, personal rights and menu items on the old
 * codes, and is then migrated to the end.
 */
class PermissionCodesMigrationTest {

    private static final String DATABASE = "permission_codes_upgrade";
    private static final Pattern MAPPING_ROW = Pattern.compile("\\['([a-z_.]+)', '([a-z_.]+)', '([a-z_.]+)'\\]");

    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static Map<Long, Set<String>> effectiveBefore;
    private static long navigationRevisionBefore;
    private static Map<Long, Long> versionsBefore;

    @BeforeAll
    static void migrateWithLegacyGrants() {
        TestDatabases.createDatabase(DATABASE);
        dataSource = TestDatabases.pooled(TestDatabases.jdbcUrl(DATABASE), TestDatabases.USER, null, 2, DATABASE);
        jdbc = JdbcClient.create(dataSource);
        flyway("146").migrate();

        var permissions = new MdPermissionRepository(jdbc);
        // One user per role, system and instance roles alike, and one with personal rights only.
        List<Long> roles = jdbc.sql("select id from md_roles order by id")
                .query(Long.class)
                .list();
        long custom = jdbc.sql("insert into md_roles (name, pcode) values ('Legacy grants', null) returning id")
                .query(Long.class)
                .single();
        jdbc.sql("""
                insert into md_role_permissions (role_id, form_code, action)
                select :role, form_code, action from md_form_actions
                where (form_code, action) in (('rbac.roles', 'grant'), ('iam.org_units', 'assign'),
                                              ('platform.webhooks', 'manage'), ('platform.search', 'view'),
                                              ('tasks.items', 'view'))
                """).param("role", custom).update();
        for (long role : concat(roles, custom)) {
            long user = user("role" + role);
            jdbc.sql("insert into md_user_roles (user_id, role_id) values (:user, :role)")
                    .param("user", user)
                    .param("role", role)
                    .update();
            permissions.recalculateEffectivePermissions(user);
        }
        long personal = user("personal");
        jdbc.sql("""
                insert into md_user_permissions (user_id, form_code, action) values
                    (:user, 'iam.users', 'view'), (:user, 'platform.files', 'upload'),
                    (:user, 'platform.announcements', 'publish'), (:user, 'notes', 'view')
                """).param("user", personal).update();
        permissions.recalculateEffectivePermissions(personal);

        jdbc.sql("""
                insert into md_navigation_items (code, title, url, required_permission) values
                    ('legacy-guarded', 'Guarded', '/settings', 'platform.navigation.manage'),
                    ('legacy-scope', 'Scope', '/iam/org-units', 'iam.org_units.assign'),
                    ('current', 'Current', '/tasks', 'tasks.items.view')
                """).update();
        navigationRevisionBefore = navigationRevision("legacy-guarded");
        effectiveBefore = effective();
        versionsBefore = versions();

        flyway(null).migrate();
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @Test
    @DisplayName("4.4: every user keeps the same effective rights, now under the new codes")
    void everyUserKeepsTheSameEffectiveRights() {
        Map<Long, Set<String>> expected = new TreeMap<>();
        effectiveBefore.forEach((user, rights) -> expected.put(
                user,
                rights.stream()
                        .map(PermissionAreas::currentPermission)
                        .collect(Collectors.toCollection(TreeSet::new))));

        assertThat(effective()).isEqualTo(expected);
        assertThat(expected.values().stream().flatMap(Set::stream))
                .contains("md.roles.grant", "md.org_units.assign", "webhook.subscriptions.manage", "search.view")
                .contains("md.users.view", "mf.files.upload", "notify.announcements.publish", "notes.view");

        // The materialized set agrees with the grants it is made from.
        var permissions = new MdPermissionRepository(jdbc);
        effectiveBefore.keySet().forEach(permissions::recalculateEffectivePermissions);
        assertThat(effective()).isEqualTo(expected);
    }

    @Test
    @DisplayName("4.4: no table keeps an old code; the catalog holds the new forms with the owning module")
    void noOldCodeIsLeft() {
        Set<String> legacy = PermissionAreas.LEGACY_FORMS.keySet();
        for (String sql : List.of(
                "select code from md_forms",
                "select form_code from md_form_actions",
                "select form_code from md_role_permissions",
                "select form_code from md_user_permissions",
                "select form_code from md_effective_permissions")) {
            assertThat(jdbc.sql(sql).query(String.class).list()).as(sql).doesNotContainAnyElementsOf(legacy);
        }
        Map<String, String> modules = jdbc
                .sql("select code, module from md_forms")
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        PermissionAreas.LEGACY_FORMS
                .values()
                .forEach(current -> assertThat(modules.get(current))
                        .as(current)
                        .isEqualTo(PermissionAreas.ownerOf(current).orElseThrow()));
    }

    @Test
    @DisplayName("4.4: menu items follow their right; revisions and permission versions move")
    void menuItemsAndVersionsFollow() {
        Map<String, String> required = jdbc
                .sql(
                        "select code, required_permission from md_navigation_items where code in ('legacy-guarded', 'legacy-scope', 'current')")
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(required)
                .containsEntry("legacy-guarded", "md.navigation.manage")
                .containsEntry("legacy-scope", "md.org_units.assign")
                .containsEntry("current", "tasks.items.view");
        assertThat(navigationRevision("legacy-guarded")).isEqualTo(navigationRevisionBefore + 1);
        versionsBefore.forEach(
                (user, version) -> assertThat(versions().get(user)).isEqualTo(version + 1));
    }

    @Test
    @DisplayName("4.4: the migration and the request-side mapping name the same old and new codes")
    void migrationAndLegacyMappingAgree() throws IOException {
        String sql = new ClassPathResource("db/migration/V147__permission_codes_by_module.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> migrated = new LinkedHashMap<>();
        Matcher row = MAPPING_ROW.matcher(sql);
        while (row.find()) {
            migrated.put(row.group(1), row.group(2));
            // V147 is frozen: the module it wrote as kwh was renamed to webhook by V155 (plan 10/10, item 4.3).
            assertThat(row.group(3).equals("kwh") ? "webhook" : row.group(3))
                    .as(row.group(2))
                    .isEqualTo(PermissionAreas.ownerOf(row.group(2)).orElseThrow());
        }
        assertThat(migrated).isEqualTo(PermissionAreas.LEGACY_FORMS);
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

    private static long user(String login) {
        return jdbc.sql("insert into md_users (name, login, email) values (:login, :login, :email) returning id")
                .param("login", "perm-" + login)
                .param("email", "perm-" + login + "@example.test")
                .query(Long.class)
                .single();
    }

    private static Map<Long, Set<String>> effective() {
        Map<Long, Set<String>> rights = new TreeMap<>();
        jdbc.sql("select user_id, form_code || '.' || action from md_effective_permissions")
                .query((rs, row) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .forEach(entry -> rights.computeIfAbsent(entry.getKey(), key -> new TreeSet<>())
                        .add(entry.getValue()));
        return rights;
    }

    private static Map<Long, Long> versions() {
        return jdbc
                .sql("select user_id, permissions_version from md_user_permission_versions")
                .query((rs, row) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static long navigationRevision(String code) {
        return jdbc.sql("select revision from md_navigation_items where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private static List<Long> concat(List<Long> roles, long extra) {
        return java.util.stream.Stream.concat(roles.stream(), java.util.stream.Stream.of(extra))
                .toList();
    }
}
