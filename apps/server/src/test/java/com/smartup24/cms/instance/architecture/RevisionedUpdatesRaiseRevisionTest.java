package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.6 (ADR-0024): the revision of a record names its state, so every write of a revisioned row raises
 * it — not only the save of the form. A write that leaves the revision as it was (a reset of 2FA, a reorder, a change
 * of roles) is silently undone by the next save of a form opened before it, because that save still matches the old
 * revision. The tables are those V135 gave a {@code revision} column, the module registry (V142) and the row that holds
 * the revision of the system settings (V142).
 */
class RevisionedUpdatesRaiseRevisionTest {

    private static final Path MAIN = Path.of("src/main/java");

    static final List<String> REVISIONED_TABLES = List.of(
            "ms_notes",
            "ms_task_projects",
            "md_users",
            "md_roles",
            "md_custom_fields",
            "md_org_units",
            "md_navigation_items",
            "ms_task_statuses",
            "ms_task_types",
            "kwh_subscriptions",
            "md_installed_modules",
            "md_settings_revision");

    private static final Pattern UPDATE =
            Pattern.compile("\\bupdate\\s+(" + String.join("|", REVISIONED_TABLES) + ")\\b", Pattern.CASE_INSENSITIVE);

    /** Where the SQL of a statement ends in the source: the text block closes or the call goes on. */
    private static final List<String> STATEMENT_ENDS = List.of("\"\"\"", ".param(", ".query(", ".update()", ";");

    private static final Pattern RAISES = Pattern.compile("revision\\s*=\\s*revision\\s*\\+\\s*1");

    @Test
    @DisplayName("3.6: every UPDATE of a revisioned table raises its revision")
    void everyUpdateRaisesTheRevision() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                Matcher update = UPDATE.matcher(source);
                while (update.find()) {
                    String where = name + ":" + line(source, update.start()) + " " + update.group(1);
                    found.add(where);
                    if (!RAISES.matcher(statement(source, update.end())).find()) {
                        missing.add(where);
                    }
                }
            }
        }
        assertThat(found).as("the scan finds the updates of revisioned tables").hasSizeGreaterThan(20);
        assertThat(missing)
                .as("UPDATEs of a revisioned table that do not set revision = revision + 1")
                .isEmpty();
    }

    @Test
    @DisplayName("3.6: the statement check sees a missing revision")
    void statementCheckSeesAMissingRevision() {
        String without = "sql(\"update ms_task_types set order_no = :orderNo where id = :id\").param(\"id\", id)";
        String with = "sql(\"\"\"\nupdate md_users set state = :s,\n revision = revision + 1\nwhere id = :id\n\"\"\")";
        Matcher first = UPDATE.matcher(without);
        Matcher second = UPDATE.matcher(with);

        assertThat(first.find()).isTrue();
        assertThat(RAISES.matcher(statement(without, first.end())).find()).isFalse();
        assertThat(second.find()).isTrue();
        assertThat(RAISES.matcher(statement(with, second.end())).find()).isTrue();
    }

    private static String statement(String source, int from) {
        int end = source.length();
        for (String marker : STATEMENT_ENDS) {
            int at = source.indexOf(marker, from);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return source.substring(from, end);
    }

    private static int line(String source, int offset) {
        return source.substring(0, offset).split("\n", -1).length;
    }
}
