package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The immutable declarations, the active version and lifecycle of each form and their quarantine; record writes stay in
 * EntityRecordStore (ADR-0038, ADR-0041).
 */
@Repository
public class StudioFormRepository {
    /** Stored forms, active and archived: the activation refuses a form beyond it (ADR-0041). */
    public static final int MAX_STORED = 500;

    /** One row beyond the stored limit, so a catalog above it is seen rather than silently cut. */
    public static final int READ_LIMIT = MAX_STORED + 1;

    private static final String VERSION_COLUMNS = """
            (select count(*) from studio_forms) as forms,
            (select coalesce(max(id), 0) from studio_forms) as last_form,
            (select count(*) from studio_form_quarantine) as held,
            (select coalesce(max(id), 0) from studio_form_quarantine) as last_held,
            (select coalesce(max(id), 0) from studio_form_versions) as last_version,
            (select coalesce(sum(revision), 0) from studio_form_state) as states""";

    /** Whether a form is archived, over {@code studio_forms f}. */
    public static final String ARCHIVED =
            "exists (select 1 from studio_form_state a where a.form_id = f.id and a.status = 'archived')";

    private final JdbcClient jdbc;
    private final JsonColumns json;

    /**
     * The state of the catalog its tables are in. Identities only grow, so equal counts and maxima mean equal rows:
     * a new row raises the maximum, and with the maximum unchanged only removals remain, which lower the count.
     * Versions are never removed, so their maximum alone tells them; state rows are never removed and each change
     * raises the revision of its row, so the sum of the revisions grows with every activation, archive or restore.
     */
    public record Version(long forms, long lastForm, long held, long lastHeld, long lastVersion, long states) {
        public Version(long forms, long lastForm, long held, long lastHeld) {
            this(forms, lastForm, held, lastHeld, 0, 0);
        }
    }

    /**
     * A stored form as raw JSON, read and compiled one at a time by the caller: the declaration of its active version,
     * whether it is archived (served read-only) and its quarantine.
     */
    public record Row(
            long id,
            String code,
            String declaration,
            @Nullable String quarantine,
            boolean archived) {
        public Row(long id, String code, String declaration, @Nullable String quarantine) {
            this(id, code, declaration, quarantine, false);
        }
    }

    /** The rows and the version of one statement, so both come from the same database snapshot. */
    public record Catalog(Version version, List<Row> rows) {}

    public record Held(long formId, String code, String reason, Instant quarantinedAt, long quarantinedBy) {}

    public StudioFormRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        json = new JsonColumns(mapper, "studio_forms");
    }

    public Version version() {
        return jdbc.sql("select " + VERSION_COLUMNS)
                .query((rs, row) -> version(rs))
                .single();
    }

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(
                rs.getLong("forms"),
                rs.getLong("last_form"),
                rs.getLong("held"),
                rs.getLong("last_held"),
                rs.getLong("last_version"),
                rs.getLong("states"));
    }

    public Catalog catalog() {
        var rows = jdbc.sql("select f.id, f.code, coalesce(v.declaration, f.declaration)::text as declaration,"
                        + " q.reason, coalesce(s.status = 'archived', false) as archived, " + VERSION_COLUMNS
                        + " from (select 1) as one"
                        + " left join (select id, code, declaration from studio_forms order by id limit "
                        + READ_LIMIT + ") as f on true"
                        + " left join studio_form_state s on s.form_id = f.id"
                        + " left join studio_form_versions v on v.form_id = f.id and v.version = s.active_version"
                        + " left join studio_form_quarantine q on q.form_id = f.id order by f.id")
                .query((rs, row) -> new Line(
                        version(rs),
                        rs.getObject("id") == null
                                ? null
                                : new Row(
                                        rs.getLong("id"),
                                        rs.getString("code"),
                                        rs.getString("declaration"),
                                        rs.getString("reason"),
                                        rs.getBoolean("archived"))))
                .list();
        return new Catalog(
                rows.getFirst().version(),
                rows.stream().map(Line::row).filter(Objects::nonNull).toList());
    }

    private record Line(Version version, @Nullable Row row) {}

    public Declaration declaration(String stored) {
        return json.read(stored, Declaration.class);
    }

    public void lockCatalog() {
        jdbc.sql("select pg_advisory_xact_lock(756402005)")
                .query((rs, row) -> true)
                .single();
    }

    public Optional<String> checksum(String code) {
        return jdbc.sql("select checksum from studio_forms where code = :code")
                .param("code", code)
                .query(String.class)
                .optional();
    }

    public Optional<Long> id(String code) {
        return jdbc.sql("select id from studio_forms where code = :code")
                .param("code", code)
                .query(Long.class)
                .optional();
    }

    /** The forms that count toward the limit of active forms: an archived form does not (ADR-0041). */
    public long count() {
        return jdbc.sql("select count(*) from studio_forms f where not " + ARCHIVED)
                .query(Long.class)
                .single();
    }

    /** Every stored form, active or archived. */
    public long stored() {
        return jdbc.sql("select count(*) from studio_forms").query(Long.class).single();
    }

    public long insert(String code, Declaration declaration, String checksum, long userId) {
        return jdbc.sql("insert into studio_forms (code, declaration, checksum, created_by) "
                        + "values (:code, cast(:declaration as jsonb), :checksum, :userId) returning id")
                .param("code", code)
                .param("declaration", json.write(declaration))
                .param("checksum", checksum)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Empty when the form is already held; the unique form key keeps one quarantine row per form. */
    public Optional<Held> quarantine(long formId, String reason, long userId) {
        return jdbc.sql("insert into studio_form_quarantine (form_id, reason, quarantined_by) "
                        + "values (:formId, :reason, :userId) on conflict (form_id) do nothing "
                        + "returning form_id, reason, quarantined_at, quarantined_by, "
                        + "(select code from studio_forms where id = :formId) as code")
                .param("formId", formId)
                .param("reason", reason)
                .param("userId", userId)
                .query((rs, row) -> held(rs))
                .optional();
    }

    public Optional<Held> held(long formId) {
        return jdbc.sql("select q.form_id, f.code, q.reason, q.quarantined_at, q.quarantined_by "
                        + "from studio_form_quarantine q join studio_forms f on f.id = q.form_id "
                        + "where q.form_id = :formId")
                .param("formId", formId)
                .query((rs, row) -> held(rs))
                .optional();
    }

    public boolean release(long formId) {
        return jdbc.sql("delete from studio_form_quarantine where form_id = :formId")
                        .param("formId", formId)
                        .update()
                > 0;
    }

    /** Bounded by the catalog: one row per stored form at most. */
    public List<Held> held() {
        return jdbc.sql("select q.form_id, f.code, q.reason, q.quarantined_at, q.quarantined_by "
                        + "from studio_form_quarantine q join studio_forms f on f.id = q.form_id order by f.code limit "
                        + READ_LIMIT)
                .query((rs, row) -> held(rs))
                .list();
    }

    private static Held held(ResultSet rs) throws SQLException {
        return new Held(
                rs.getLong("form_id"),
                rs.getString("code"),
                rs.getString("reason"),
                rs.getObject("quarantined_at", OffsetDateTime.class).toInstant(),
                rs.getLong("quarantined_by"));
    }
}
