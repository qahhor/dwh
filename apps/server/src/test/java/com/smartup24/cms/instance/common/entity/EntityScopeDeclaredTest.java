package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 5.3, acceptance "the scope is declared for 100% of entities" (ADR-0032, 5.1): every entity the
 * application runs declares which rows a viewer sees, and its table has what the declaration promises — the scope's
 * columns, the {@code revision} of ADR-0024 and, for the ARCHIVE capability, {@code archived_at}/{@code archived_by}
 * with unique indexes that ignore archived rows (ADR-0032, 5.4). The test lists every entity with its scope for review;
 * an entity without a scope is not even built ({@link EntityScopeTest}), and one that slipped through fails here.
 */
class EntityScopeDeclaredTest extends EmbeddedPostgresTest {

    /** The capabilities that need rows: an entity without a table cannot promise them. */
    private static final Set<EntityCapability> NEED_ROWS = Set.of(
            EntityCapability.EXPORT, EntityCapability.SAVED_VIEWS, EntityCapability.BULK, EntityCapability.ARCHIVE);

    @Autowired
    private List<EntityDefinition> entities;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("5.3: every entity declares its scope, and its table has the scope's columns and its revision")
    void everyEntityDeclaresItsScope() {
        Map<String, String> review = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            EntityModel model = entity.model();
            if (model == null) {
                // A form without a table has no rows to restrict; it may not promise anything that needs them.
                review.put(entity.code(), "no table");
                if (entity.capabilities().stream().anyMatch(NEED_ROWS::contains)) {
                    problems.add(entity.code() + ": capabilities that need rows without a table");
                }
                continue;
            }
            // The model refuses a missing scope, so a declaration cannot reach here without one.
            review.put(entity.code(), model.scope().describe());
            List<String> needed = new ArrayList<>(List.of("id", "revision"));
            needed.addAll(model.scope().columns());
            for (String column : needed) {
                if (columnType(model.table(), column) == null) {
                    problems.add(entity.code() + ": " + model.table() + " has no column " + column);
                }
            }
            if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
                problems.addAll(archiveProblems(entity.code(), model.table()));
            }
        }
        assertThat(review)
                .as("every entity the application runs, with its scope for review")
                .hasSameSizeAs(entities)
                .containsEntry("ms.notes", "owner(created_by)");
        assertThat(problems).as("scopes: %s", review).isEmpty();
    }

    @Test
    @DisplayName(
            "5.3: a unique index of an archivable table ignores archived rows, and the check catches one that does not")
    void uniqueIndexesOfAnArchivableTableIgnoreArchivedRows() {
        String table = "test_archive_probe_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("create table " + table + " (id bigint generated always as identity primary key, code text not null,"
                        + " archived_at timestamptz, archived_by bigint references md_users (id),"
                        + " revision bigint not null default 1)")
                .update();
        try {
            jdbc.sql("create index " + table + "_archived_by_idx on " + table + " (archived_by)")
                    .update();
            jdbc.sql("create unique index " + table + "_code_uq on " + table + " (code)")
                    .update();
            assertThat(archiveProblems("probe", table))
                    .as("a unique index over every row")
                    .singleElement()
                    .asString()
                    .contains(table + "_code_uq");

            jdbc.sql("drop index " + table + "_code_uq").update();
            jdbc.sql("create unique index " + table + "_code_uq on " + table + " (code) where archived_at is null")
                    .update();
            assertThat(archiveProblems("probe", table)).isEmpty();

            // An archived code is free again; two codes in use still clash.
            jdbc.sql("insert into " + table + " (code, archived_at) values ('A', clock_timestamp())")
                    .update();
            jdbc.sql("insert into " + table + " (code) values ('A')").update();
            assertThatThrownBy(() -> jdbc.sql("insert into " + table + " (code) values ('A')")
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.sql("drop table " + table).update();
        }
    }

    /**
     * What the ARCHIVE capability needs of a table (ADR-0032, 5.4): {@code archived_at timestamptz},
     * {@code archived_by bigint} referencing a user with its index, and every unique index other than the key partial
     * {@code where archived_at is null}, so an archived row does not hold its code.
     */
    private List<String> archiveProblems(String code, String table) {
        List<String> problems = new ArrayList<>();
        if (!"timestamp with time zone".equals(columnType(table, "archived_at"))) {
            problems.add(code + ": " + table + ".archived_at timestamptz is missing");
        }
        if (!"bigint".equals(columnType(table, "archived_by"))) {
            problems.add(code + ": " + table + ".archived_by bigint is missing");
        }
        boolean referencesUsers =
                jdbc.sql("""
                        select exists (
                            select 1 from pg_constraint c
                            join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
                            where c.contype = 'f' and c.conrelid = cast(:table as regclass)
                              and a.attname = 'archived_by' and c.confrelid = cast('md_users' as regclass))
                        """).param("table", table).query(Boolean.class).single();
        if (!referencesUsers) {
            problems.add(code + ": " + table + ".archived_by does not reference md_users");
        }
        List<String[]> indexes = jdbc.sql("""
                        select indexname, indexdef from pg_indexes
                        where schemaname = current_schema() and tablename = :table
                        """)
                .param("table", table)
                .query((rs, row) -> new String[] {rs.getString("indexname"), rs.getString("indexdef")})
                .list();
        if (indexes.stream().noneMatch(index -> index[1].matches("(?s).*\\(archived_by\\).*"))) {
            problems.add(code + ": " + table + ".archived_by has no index");
        }
        for (String[] index : indexes) {
            boolean unique = index[1].startsWith("CREATE UNIQUE INDEX");
            if (unique && !index[0].endsWith("_pkey") && !index[1].contains("WHERE (archived_at IS NULL)")) {
                problems.add(code + ": unique index " + index[0] + " also holds archived rows");
            }
        }
        return problems;
    }

    private @Nullable String columnType(String table, String column) {
        return jdbc.sql("""
                        select data_type from information_schema.columns
                        where table_schema = current_schema() and table_name = :table and column_name = :column
                        """)
                .param("table", table)
                .param("column", column)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
