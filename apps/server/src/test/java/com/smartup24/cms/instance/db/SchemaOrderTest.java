package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 4.6: order in the schema, checked on a database with every migration applied. No index repeats
 * another, every reference column has a foreign key unless it is listed here with its reason, and the extra fields of
 * a record are always a JSON object. The secrets part is {@link StoredSecretsSchemaTest}.
 */
class SchemaOrderTest {

    /**
     * Reference-like columns that rightly have no foreign key, each with its reason. A column leaves the list as soon
     * as it gets a key: the test fails on a stale entry as on a new column.
     */
    static final Map<String, String> WITHOUT_FOREIGN_KEY = Map.ofEntries(
            Map.entry("audit_log.session_id", "audit: the record outlives the session, which retention deletes"),
            Map.entry("security_events.user_id", "security journal: an event keeps the account id it was about"),
            Map.entry("fnd_job_runs.queue_id", "history: the queue row is deleted when its job ends"),
            Map.entry("md_navigation_items.section_id", "a code of a menu section declared in code, not a row"),
            Map.entry("md_sso_providers.client_id", "an identifier issued by the external identity provider"),
            Map.entry("md_sso_providers.provider_id", "the natural key of the row itself (google, oneid)"),
            Map.entry("report_exports.public_id", "the public identifier of the row itself"),
            Map.entry("upl_packages.public_id", "the public identifier of the row itself"),
            Map.entry("search_jobs.request_id", "the idempotency key sent by the caller"),
            Map.entry(
                    "search_jobs.requested_generation_id",
                    "request metadata: the generation the caller named, kept even when it does not exist"),
            Map.entry(
                    "search_generations.discovery_after_id",
                    "a keyset cursor over task, project or user ids (polymorphic)"),
            Map.entry("search_projection_versions.entity_id", "polymorphic: entity_type + entity_id over three tables"),
            Map.entry("mf_record_files.record_id", "polymorphic: entity + record_id over the tables of every entity"),
            Map.entry("upl_sources.created_by", "the actor name of the foundation as text (user id or system)"),
            Map.entry("upl_sources.modified_by", "the actor name of the foundation as text (user id or system)"));

    /** Indexes made redundant by another index of the same table: same leading keys, order, predicate and method. */
    static final String REDUNDANT_INDEXES = """
            select a.indexrelid::regclass::text || ' repeats ' || b.indexrelid::regclass::text
            from pg_index a
            join pg_index b on b.indrelid = a.indrelid and b.indexrelid <> a.indexrelid
            join pg_class ia on ia.oid = a.indexrelid
            join pg_class ib on ib.oid = b.indexrelid
            join pg_class t on t.oid = a.indrelid
            where t.relnamespace = 'public'::regnamespace
              and ia.relam = ib.relam
              and a.indisvalid and b.indisvalid
              and a.indnkeyatts <= b.indnkeyatts
              and (a.indkey::int2[])[0:a.indnkeyatts - 1] = (b.indkey::int2[])[0:a.indnkeyatts - 1]
              and (a.indclass::oid[])[0:a.indnkeyatts - 1] = (b.indclass::oid[])[0:a.indnkeyatts - 1]
              and (a.indoption::int2[])[0:a.indnkeyatts - 1] = (b.indoption::int2[])[0:a.indnkeyatts - 1]
              and coalesce(pg_get_expr(a.indexprs, a.indrelid), '') = coalesce(pg_get_expr(b.indexprs, b.indrelid), '')
              and coalesce(pg_get_expr(a.indpred, a.indrelid), '') = coalesce(pg_get_expr(b.indpred, b.indrelid), '')
              and (not a.indisunique or (b.indisunique and a.indnkeyatts = b.indnkeyatts))
              and (a.indnkeyatts < b.indnkeyatts or a.indisunique <> b.indisunique or a.indexrelid > b.indexrelid)
            order by 1
            """;

    /** Columns named like references ({@code *_id}, {@code created_by} …) of tables without a foreign key on them. */
    static final String REFERENCE_COLUMNS_WITHOUT_KEY = """
            select c.relname || '.' || a.attname
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            where c.relnamespace = 'public'::regnamespace
              and c.relkind in ('r', 'p')
              and not c.relispartition
              and a.attnum > 0 and not a.attisdropped
              and (a.attname like '%\\_id' or a.attname in ('created_by', 'modified_by', 'updated_by', 'deleted_by'))
              and c.relname <> 'flyway_schema_history'
              and not exists (
                  select 1 from pg_constraint k
                  where k.contype = 'f' and k.conrelid = c.oid and a.attnum = any (k.conkey))
            order by 1
            """;

    /** Every jsonb column named attributes, with whether a validated check keeps it an object. */
    static final String ATTRIBUTES_COLUMNS = """
            select c.relname as table_name,
                   exists (
                       select 1 from pg_constraint k
                       where k.conrelid = c.oid and k.contype = 'c' and k.convalidated
                         and pg_get_constraintdef(k.oid) like '%jsonb_typeof(attributes) = ''object''%'
                   ) as checked
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            where c.relnamespace = 'public'::regnamespace
              and c.relkind in ('r', 'p')
              and not c.relispartition
              and a.attname = 'attributes' and a.atttypid = 'jsonb'::regtype and not a.attisdropped
            order by 1
            """;

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        DataSource database = TestDatabases.migratedCopy("schema_order");
        jdbc = JdbcClient.create(database);
    }

    @Test
    @DisplayName("4.6: no index repeats the leading keys of another index of its table")
    void noRedundantIndexes() {
        assertThat(jdbc.sql(REDUNDANT_INDEXES).query(String.class).list())
                .as("redundant indexes")
                .isEmpty();
    }

    @Test
    @DisplayName("4.6: every reference column has a foreign key, except the listed ones")
    void referenceColumnsHaveForeignKeys() {
        List<String> found =
                jdbc.sql(REFERENCE_COLUMNS_WITHOUT_KEY).query(String.class).list();
        assertThat(new TreeSet<>(found))
                .as("reference columns without a foreign key; add a key or list the column with its reason")
                .isEqualTo(new TreeSet<>(WITHOUT_FOREIGN_KEY.keySet()));
    }

    @Test
    @DisplayName("4.6: the attributes of every record are kept a JSON object by a validated check")
    void attributesAreObjects() {
        Map<String, Boolean> columns = new TreeMap<>();
        jdbc.sql(ATTRIBUTES_COLUMNS)
                .query((rs, rowNum) -> columns.put(rs.getString("table_name"), rs.getBoolean("checked")))
                .list();
        assertThat(columns)
                .containsOnlyKeys("md_installed_modules", "md_users", "ms_notes", "ms_task_projects", "ms_tasks")
                .allSatisfy((table, checked) -> assertThat(checked).as(table).isTrue());
    }

    @Test
    @DisplayName("4.6: the due date of tasks is indexed (the intent of V031, met by the index of V016)")
    void taskDueDateIsIndexed() {
        // V031 asked for a partial index under the name V016 had already used, so "if not exists" skipped it. The
        // full index of V016 serves the same range and overdue filters (a btree range scan skips nulls) and the sort
        // by due date as well, so a second, partial copy would only be a near duplicate.
        Integer indexes = jdbc.sql("""
                        select count(*) from pg_index i
                        where i.indrelid = 'ms_tasks'::regclass and i.indisvalid
                          and i.indkey[0] = (select attnum from pg_attribute
                                             where attrelid = 'ms_tasks'::regclass and attname = 'end_time')
                        """).query(Integer.class).single();
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    @DisplayName("4.6: the checks see a repeated index and a reference column without a key")
    void checksSeeWhatTheyLookFor() {
        // A database of its own: the probe table never meets the checks of the real schema above.
        JdbcClient probe = JdbcClient.create(TestDatabases.migratedCopy("schema_order_probe"));
        probe.sql("create table zz_schema_probe (id bigint primary key, owner_id bigint, code text)")
                .update();
        probe.sql("create index zz_schema_probe_owner_idx on zz_schema_probe (owner_id)")
                .update();
        probe.sql("create index zz_schema_probe_owner_code_idx on zz_schema_probe (owner_id, code)")
                .update();
        probe.sql("create index zz_schema_probe_code_idx on zz_schema_probe (code)")
                .update();
        probe.sql("create index zz_schema_probe_code_copy_idx on zz_schema_probe (code)")
                .update();
        probe.sql("create unique index zz_schema_probe_owner_uq on zz_schema_probe (owner_id)")
                .update();

        assertThat(probe.sql(REDUNDANT_INDEXES).query(String.class).list())
                .containsExactlyInAnyOrder(
                        "zz_schema_probe_code_copy_idx repeats zz_schema_probe_code_idx",
                        "zz_schema_probe_owner_idx repeats zz_schema_probe_owner_code_idx",
                        "zz_schema_probe_owner_idx repeats zz_schema_probe_owner_uq");
        assertThat(probe.sql(REFERENCE_COLUMNS_WITHOUT_KEY).query(String.class).list())
                .contains("zz_schema_probe.owner_id");
    }
}
