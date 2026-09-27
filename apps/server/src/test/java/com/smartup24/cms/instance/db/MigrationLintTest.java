package com.smartup24.cms.instance.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 10/10, item 1.7: new migrations follow the database naming and type rules of
 * {@code docs/adr/ADR-0020-database-naming.md}.
 *
 * <p>Released migrations never change (the manifest), so the rules start at {@link #FIRST_LINTED}: the drift of the
 * old files ({@code idx_} and {@code _idx}, {@code updated_at} and {@code modified_at}, {@code bigserial},
 * {@code varchar(n)}) stays where it is and does not grow.
 */
class MigrationLintTest {

    /** The first version the rules apply to; V127 was the last file before them. */
    static final int FIRST_LINTED = 128;

    /** Tables large enough that a plain index build blocks writes: their indexes are built CONCURRENTLY. */
    static final Set<String> LARGE_TABLES = Set.of("security_events", "kauth_login_attempts", "kauth_sessions",
            "idempotency_keys", "md_users", "mf_files", "ms_tasks", "ms_task_comments", "ms_notifications",
            "ms_notification_outbox", "kwh_outbox", "kwh_logs", "fnd_load_log", "upl_package_errors",
            "search_generation_delivery", "search_index_state");

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern DOLLAR_BODY = Pattern.compile("(?s)\\$\\$.*?\\$\\$");
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");

    private static final Pattern SERIAL = Pattern.compile("(?i)\\b(big|small)?serial\\b");
    private static final Pattern IDENTITY_BY_DEFAULT = Pattern.compile("(?i)generated\\s+by\\s+default\\s+as\\s+identity");
    private static final Pattern FIXED_CHAR = Pattern.compile("(?i)\\b(varchar|character\\s+varying|char|character)\\s*\\(");
    private static final Pattern TIMESTAMP_WITHOUT_TZ = Pattern.compile("(?i)\\btimestamp\\b(?!\\s+with\\s+time\\s+zone)");
    private static final Pattern UPDATED_AT = Pattern.compile("(?i)\\bupdated_at\\b");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^create\\s+(unique\\s+)?index\\s+(concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?(\\w+)\\s+on\\s+(?:only\\s+)?(\\w+)");
    private static final Pattern TABLE_STATEMENT = Pattern.compile(
            "(?is)^(?:create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?|alter\\s+table\\s+(?:only\\s+)?)(\\w+)");
    private static final Pattern CONSTRAINT = Pattern.compile(
            "(?is)\\bconstraint\\s+(\\w+)\\s+(primary\\s+key|unique|foreign\\s+key|check|exclude)");
    private static final Pattern CONCURRENTLY = Pattern.compile("(?i)\\bconcurrently\\b");
    private static final Pattern CONCURRENT_OR_SETTING = Pattern.compile(
            "(?is)^(set\\s+.*|(create|drop)\\s+(unique\\s+)?index\\s+concurrently\\b.*)");

    record Violation(String file, String rule, String detail) {}

    @Test
    @DisplayName("1.7: migrations from V128 follow the naming and type rules")
    void newMigrationsFollowTheRules() throws IOException {
        List<Violation> violations = new ArrayList<>();
        for (Resource file : resources("classpath*:db/migration/V*.sql")) {
            Matcher version = VERSION.matcher(file.getFilename());
            if (version.matches() && Integer.parseInt(version.group(1)) >= FIRST_LINTED) {
                violations.addAll(lint(file.getFilename(), file.getContentAsString(StandardCharsets.UTF_8)));
            }
        }
        for (Resource file : resources("classpath*:migration-fixtures/lint-good/V*.sql")) {
            violations.addAll(lint(file.getFilename(), file.getContentAsString(StandardCharsets.UTF_8)));
        }
        assertThat(violations).as("see docs/adr/ADR-0020-database-naming.md").isEmpty();
    }

    @Test
    @DisplayName("1.7: the violator fixture is red on every rule")
    void violatorIsRedOnEveryRule() throws IOException {
        Set<String> rules = new TreeSet<>();
        for (Resource file : resources("classpath*:migration-fixtures/lint-bad/V*.sql")) {
            lint(file.getFilename(), file.getContentAsString(StandardCharsets.UTF_8)).forEach(v -> rules.add(v.rule()));
        }
        assertThat(rules).containsExactlyInAnyOrder("serial", "identity_by_default", "fixed_char",
                "timestamp_without_time_zone", "updated_at", "index_name", "constraint_name", "large_table_index",
                "concurrently_not_alone");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "create index md_notes_owner_idx on md_notes (owner_id)|",
            "create unique index md_notes_code_uq on md_notes (code)|",
            "create index idx_md_notes_owner on md_notes (owner_id)|index_name",
            "create unique index md_notes_code_idx on md_notes (code)|index_name",
            "create index notes_owner_idx on md_notes (owner_id)|index_name",
            "alter table md_notes add constraint md_notes_ck_title check (title <> '')|",
            "alter table md_notes add constraint notes_title_check check (title <> '')|constraint_name",
            "alter table md_notes add constraint md_notes_pkey primary key (id)|",
            "create table md_notes (id bigint generated always as identity primary key, at timestamptz, "
                    + "modified_at timestamptz)|",
            "select to_char(now(), 'YYYY'), current_timestamp|"})
    @DisplayName("1.7: single statements")
    void singleStatements(String sql, String expected) {
        List<String> rules = lint("V999__probe.sql", sql + ";").stream().map(Violation::rule).toList();
        if (expected == null) {
            assertThat(rules).isEmpty();
        } else {
            assertThat(rules).containsExactly(expected);
        }
    }

    static List<Violation> lint(String file, String sql) {
        String code = STRING.matcher(DOLLAR_BODY.matcher(BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(sql)
                .replaceAll("")).replaceAll("")).replaceAll("\\$\\$body\\$\\$")).replaceAll("''");
        List<Violation> out = new ArrayList<>();
        check(out, file, code, SERIAL, "serial", "use bigint generated always as identity");
        check(out, file, code, IDENTITY_BY_DEFAULT, "identity_by_default", "use generated always as identity");
        check(out, file, code, FIXED_CHAR, "fixed_char", "use text with a check constraint");
        check(out, file, code, TIMESTAMP_WITHOUT_TZ, "timestamp_without_time_zone", "use timestamptz");
        check(out, file, code, UPDATED_AT, "updated_at", "the column is modified_at");

        List<String> statements = new ArrayList<>();
        for (String statement : code.split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.trim());
            }
        }
        for (String statement : statements) {
            Matcher index = CREATE_INDEX.matcher(statement);
            if (index.find()) {
                boolean unique = index.group(1) != null;
                boolean concurrent = index.group(2) != null;
                String name = index.group(3).toLowerCase();
                String table = index.group(4).toLowerCase();
                String suffix = unique ? "_uq" : "_idx";
                if (!name.startsWith(table + "_") || !name.endsWith(suffix)) {
                    out.add(new Violation(file, "index_name", name + ": expected " + table + "_<columns>" + suffix));
                }
                if (!concurrent && LARGE_TABLES.contains(table)) {
                    out.add(new Violation(file, "large_table_index", name + " on " + table + ": create it concurrently"));
                }
            }
            Matcher table = TABLE_STATEMENT.matcher(statement);
            if (table.find()) {
                String tableName = table.group(1).toLowerCase();
                Matcher constraint = CONSTRAINT.matcher(statement);
                while (constraint.find()) {
                    String name = constraint.group(1).toLowerCase();
                    String kind = constraint.group(2).toLowerCase().replaceAll("\\s+", " ");
                    String expected = switch (kind) {
                        case "primary key" -> tableName + "_pkey";
                        case "unique" -> tableName + "_uk_";
                        case "foreign key" -> tableName + "_fk_";
                        case "check" -> tableName + "_ck_";
                        default -> tableName + "_ex_";
                    };
                    boolean ok = kind.equals("primary key") ? name.equals(expected) : name.startsWith(expected);
                    if (!ok) {
                        out.add(new Violation(file, "constraint_name", name + ": expected " + expected
                                + (kind.equals("primary key") ? "" : "<suffix>")));
                    }
                }
            }
        }
        // CONCURRENTLY cannot run in a transaction: such a file holds nothing else, so Flyway runs it alone.
        if (CONCURRENTLY.matcher(code).find()
                && statements.stream().anyMatch(statement -> !CONCURRENT_OR_SETTING.matcher(statement).matches())) {
            out.add(new Violation(file, "concurrently_not_alone", "an index built concurrently needs a file of its own"));
        }
        return out;
    }

    private static void check(List<Violation> out, String file, String code, Pattern pattern, String rule, String hint) {
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            out.add(new Violation(file, rule, matcher.group() + ": " + hint));
        }
    }

    private static Resource[] resources(String pattern) throws IOException {
        return new PathMatchingResourcePatternResolver().getResources(pattern);
    }
}
