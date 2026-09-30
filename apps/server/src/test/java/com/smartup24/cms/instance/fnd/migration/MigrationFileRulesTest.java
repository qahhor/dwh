package com.smartup24.cms.instance.fnd.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.fnd.FndPref;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Migration file rules (file name, timeout header, no DDL mixed with seed data, approval of destructive changes),
 * checked from the file contents without a database.
 */
class MigrationFileRulesTest {

    /** Our files in the framework's shared catalog start at V100; everything below belongs to upstream, unchecked. */
    private static final Pattern OURS = Pattern.compile("^V([1-9]\\d{2,})__.+\\.sql$");

    private static final Pattern NAME = Pattern.compile("^V\\d{3}__[a-z0-9_]+\\.sql$");
    /** Destructive DDL anywhere at the top level (function bodies $$…$$ are cut out beforehand). */
    private static final Pattern DESTRUCTIVE_DDL = Pattern.compile(
            "(?i)\\b(DROP\\s+(TABLE|COLUMN|SCHEMA|INDEX|CONSTRAINT|FUNCTION|TRIGGER|TYPE|VIEW)|TRUNCATE"
                    + "|ALTER\\s+TABLE\\s+\\S+\\s+(DROP|RENAME|ALTER\\s+COLUMN\\s+\\S+\\s+(TYPE|SET\\s+NOT\\s+NULL)))\\b");
    /**
     * Destructive DML only at the start of a statement: `on conflict do update` and `create trigger … after update`
     * do not count.
     */
    private static final Pattern DESTRUCTIVE_DML =
            Pattern.compile("(?im)^\\s*(DELETE\\s+FROM|UPDATE\\s+\\S+\\s+SET)\\b");

    private static final Pattern DDL = Pattern.compile("(?i)^\\s*(CREATE|ALTER|DROP)\\b", Pattern.MULTILINE);
    private static final Pattern SEED = Pattern.compile("(?i)^\\s*(INSERT|COPY)\\b", Pattern.MULTILINE);
    private static final Pattern DOLLAR_BODY = Pattern.compile("\\$\\$.*?\\$\\$", Pattern.DOTALL);
    /**
     * A DO block runs at once, unlike a function body: a drop or a delete inside it (often through execute format)
     * is destructive all the same (plan 10/10, item 1.7).
     */
    private static final Pattern DO_BODY = Pattern.compile("(?is)\\bdo\\s+\\$\\$(.*?)\\$\\$");

    private static final Pattern DESTRUCTIVE_IN_BODY = Pattern.compile(
            "(?i)\\b(DROP\\s+(TABLE|COLUMN|SCHEMA|INDEX|CONSTRAINT|FUNCTION|TRIGGER|TYPE|VIEW)|TRUNCATE|DELETE\\s+FROM"
                    + "|UPDATE\\s+\\S+\\s+SET)\\b");
    /** An index built concurrently may run longer than a minute: its file may lift the statement timeout. */
    private static final Pattern CONCURRENTLY = Pattern.compile("(?i)\\bconcurrently\\b");

    record Violation(String file, String rule) {}

    @Test
    @DisplayName("AC-2: наши миграции (V1xx и db/dwh) соответствуют регламенту; файлы каркаса не проверяются")
    void realCatalogsAreClean() throws IOException {
        List<Violation> violations = new ArrayList<>();
        violations.addAll(check("classpath*:" + FndPref.OLTP_MIGRATIONS + "/V*.sql"));
        // The pg-dwh catalog is entirely ours: numbering starts at V001 there and the rules apply to every file
        violations.addAll(check("classpath*:" + FndPref.DWH_MIGRATIONS + "/V*.sql", true));
        violations.addAll(check("classpath*:migration-fixtures/good/V*.sql"));
        violations.addAll(check("classpath*:migration-fixtures/lint-good/V*.sql"));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-2: фикстура-нарушитель даёт красный по каждому правилу")
    void violatorIsRed() throws IOException {
        List<Violation> violations = check("classpath*:migration-fixtures/bad/V*.sql");
        assertThat(violations)
                .extracting(Violation::rule)
                .contains("header_timeouts", "ddl_and_seed_mixed", "destructive_not_approved");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "drop table t;",
                "drop function f();",
                "drop trigger t on x;",
                "drop type t;",
                "drop view v;",
                "drop index i;",
                "drop schema s;",
                "delete from t where id = 1;",
                "update t set a = 1;",
                "truncate t;",
                "alter table t drop column c;",
                "do $$ begin execute format('drop table %I', 'audit_log_2027_01'); end $$;",
                "do $$ begin delete from t where id = 1; end $$;"
            })
    @DisplayName("AC-2/M-6: каждое деструктивное слово — красный")
    void destructiveWordsAreRed(String sql) {
        assertThat(destructive("set lock_timeout = '2s';\nset statement_timeout = '60s';\n" + sql))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "create or replace function f() returns void language sql as $$ select 1 $$;",
                "create or replace function f() returns trigger language plpgsql as $$\nbegin\n"
                        + "  delete from t; execute 'drop trigger x on y'; update t set a = 1;\nend $$;",
                "insert into t (a) values (1) on conflict (a) do update set b = excluded.b;",
                "create trigger trg before update or delete on t for each row execute function f();"
            })
    @DisplayName("AC-2/M-6: create or replace function, do update, тела функций и триггеры — не деструктивны")
    void nonDestructiveIsGreen(String sql) {
        assertThat(destructive(sql)).isFalse();
    }

    static List<Violation> check(String pattern) throws IOException {
        return check(pattern, false);
    }

    /** {@code allOurs}: a catalog without framework files, so every file is checked, not only V1xx. */
    static List<Violation> check(String pattern, boolean allOurs) throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(pattern);
        assertThat(files).as("каталог %s не пуст", pattern).isNotEmpty();
        List<Violation> out = new ArrayList<>();
        for (Resource file : files) {
            String name = file.getFilename();
            String text = file.getContentAsString(StandardCharsets.UTF_8);
            // Framework files (V0xx) belong to upstream: we neither check nor edit them against these rules
            if (name == null || !(allOurs || OURS.matcher(name).matches())) {
                continue;
            }
            {
                if (!NAME.matcher(name).matches()) {
                    out.add(new Violation(name, "file_name"));
                }
                List<String> head = Arrays.stream(text.split("\\R"))
                        .filter(l -> !l.isBlank())
                        .limit(2)
                        .toList();
                boolean timeoutOk = head.size() >= 2
                        && (head.get(1).trim().equals("set statement_timeout = '60s';")
                                || CONCURRENTLY.matcher(text).find()
                                        && head.get(1).trim().equals("set statement_timeout = '0';"));
                if (head.size() < 2 || !head.get(0).trim().equals("set lock_timeout = '2s';") || !timeoutOk) {
                    out.add(new Violation(name, "header_timeouts"));
                }
                // Function bodies ($$ … $$) are part of DDL: an INSERT inside a trigger does not count as seed data
                String topLevel = DOLLAR_BODY.matcher(text).replaceAll("\\$\\$body\\$\\$");
                if (DDL.matcher(topLevel).find() && SEED.matcher(topLevel).find()) {
                    out.add(new Violation(name, "ddl_and_seed_mixed"));
                }
            }
            if (destructive(text) && !approved(text)) {
                out.add(new Violation(name, "destructive_not_approved"));
            }
        }
        return out;
    }

    /** Whether a migration is destructive: the top level with function bodies ($$…$$) cut out, plus DO blocks. */
    static boolean destructive(String text) {
        String topLevel = DOLLAR_BODY.matcher(text).replaceAll("\\$\\$body\\$\\$");
        if (DESTRUCTIVE_DDL.matcher(topLevel).find()
                || DESTRUCTIVE_DML.matcher(topLevel).find()) {
            return true;
        }
        Matcher doBlock = DO_BODY.matcher(text);
        while (doBlock.find()) {
            if (DESTRUCTIVE_IN_BODY.matcher(doBlock.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean approved(String text) {
        return text.contains("-- destructive: approved")
                && Pattern.compile("^-- reason: \\S", Pattern.MULTILINE)
                        .matcher(text)
                        .find()
                && Pattern.compile("^-- approved_by: \\S", Pattern.MULTILINE)
                        .matcher(text)
                        .find();
    }
}
