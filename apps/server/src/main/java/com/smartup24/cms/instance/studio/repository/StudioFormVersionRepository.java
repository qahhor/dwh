package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The versions and the state of the live forms (ADR-0041). Version 1 is the immutable {@code studio_forms} row; later
 * versions are immutable rows of {@code studio_form_versions}. A form without a state row serves version 1 and is
 * active, at revision 1; the first change writes the row at revision 2. The caller holds the catalog lock.
 */
@Repository
public class StudioFormVersionRepository {
    /** Versions of one form, the first included. */
    public static final int MAX_VERSIONS = 100;

    private static final String ACTIVE = "active";

    private static final String HEAD = """
            select f.id, f.code, f.created_at, coalesce(s.active_version, 1) as active_version,
                   coalesce(s.status, 'active') as status, coalesce(s.revision, 1) as revision,
                   coalesce(s.modified_at, f.created_at) as modified_at,
                   coalesce(v.declaration, f.declaration)::text as declaration,
                   coalesce(v.checksum, f.checksum) as checksum,
                   1 + (select count(*) from studio_form_versions c where c.form_id = f.id) as versions,
                   (select count(*) from studio_records r where r.form_id = f.id and r.archived_at is null) as records,
                   exists (select 1 from studio_form_quarantine q where q.form_id = f.id) as quarantined
            from studio_forms f
            left join studio_form_state s on s.form_id = f.id
            left join studio_form_versions v on v.form_id = f.id and v.version = s.active_version
            """;

    private final JdbcClient jdbc;
    private final JsonColumns json;

    /** A form with its active version, lifecycle and counts. */
    public record Head(
            long id,
            String code,
            Instant createdAt,
            int activeVersion,
            String status,
            long revision,
            Instant modifiedAt,
            String declaration,
            String checksum,
            int versions,
            long records,
            boolean quarantined) {
        public boolean archived() {
            return !ACTIVE.equals(status);
        }
    }

    /** One version of a form, without its declaration. */
    public record VersionRow(int version, String checksum, Instant createdAt, long createdBy) {}

    public StudioFormVersionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        json = new JsonColumns(mapper, "studio_form_versions");
    }

    public Optional<Head> head(String code) {
        return jdbc.sql(HEAD + " where f.code = :code")
                .param("code", code)
                .query((rs, row) -> head(rs))
                .optional();
    }

    /** A page of the forms, the newest first; {@code limit + 1} rows are read to tell whether more follow. */
    public List<Head> page(TimePage page) {
        TimePage.Position after = page.after();
        String where = after == null ? "" : " where (f.created_at, f.id) < (:at, :id)";
        var statement = jdbc.sql(HEAD + where + " order by f.created_at desc, f.id desc limit :limit")
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> head(rs)).list();
    }

    /** Every version of a form, the first one first; bounded by {@link #MAX_VERSIONS}. */
    public List<VersionRow> versions(long formId) {
        return jdbc.sql("""
                        select 1 as version, checksum, created_at, created_by from studio_forms where id = :formId
                        union all
                        select version, checksum, created_at, created_by from studio_form_versions
                        where form_id = :formId
                        order by version limit :limit
                        """)
                .param("formId", formId)
                .param("limit", MAX_VERSIONS)
                .query((rs, row) -> new VersionRow(
                        rs.getInt("version"),
                        rs.getString("checksum"),
                        instant(rs, "created_at"),
                        rs.getLong("created_by")))
                .list();
    }

    public long insertVersion(long formId, int version, Declaration declaration, String checksum, long userId) {
        return jdbc.sql("insert into studio_form_versions (form_id, version, declaration, checksum, created_by)"
                        + " values (:formId, :version, cast(:declaration as jsonb), :checksum, :userId) returning id")
                .param("formId", formId)
                .param("version", version)
                .param("declaration", json.write(declaration))
                .param("checksum", checksum)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Writes the state of a form at the next revision of {@code head}; the caller holds the catalog lock. */
    public long saveState(Head head, int activeVersion, boolean archived, long userId) {
        long revision = head.revision() + 1;
        jdbc.sql("""
                        insert into studio_form_state (form_id, active_version, status, revision, modified_by)
                        values (:formId, :version, :status, :revision, :userId)
                        on conflict (form_id) do update set active_version = excluded.active_version,
                            status = excluded.status, revision = excluded.revision,
                            modified_by = excluded.modified_by, modified_at = clock_timestamp()
                        """)
                .param("formId", head.id())
                .param("version", activeVersion)
                .param("status", archived ? "archived" : ACTIVE)
                .param("revision", revision)
                .param("userId", userId)
                .update();
        return revision;
    }

    private static Head head(ResultSet rs) throws SQLException {
        return new Head(
                rs.getLong("id"),
                rs.getString("code"),
                instant(rs, "created_at"),
                rs.getInt("active_version"),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs, "modified_at"),
                rs.getString("declaration"),
                rs.getString("checksum"),
                rs.getInt("versions"),
                rs.getLong("records"),
                rs.getBoolean("quarantined"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** The declaration of a stored version, read with the JSON of the application. */
    public Declaration declaration(String stored) {
        return json.read(stored, Declaration.class);
    }
}
