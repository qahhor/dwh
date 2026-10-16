package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Internal authoring tables; row locking serializes sealing with draft saves (ADR-0035). */
@Repository
public class MdFormStudioRepository {
    private static final String DRAFT = "entity_code, revision, declaration::text as declaration";
    private static final String VERSION =
            "id, entity_code, version, source_revision, checksum, package::text as package, created_at";
    private final JdbcClient jdbc;

    public MdFormStudioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Draft(String entityCode, long revision, String declaration) {}

    public record StoredVersion(
            long id,
            String entityCode,
            long version,
            long sourceRevision,
            String checksum,
            String artifact,
            Instant createdAt) {}

    public Optional<Draft> findDraft(String code, boolean lock) {
        return jdbc.sql("select " + DRAFT + " from md_form_layout_drafts where entity_code = :code"
                        + (lock ? " for update" : ""))
                .param("code", code)
                .query(MdFormStudioRepository::draft)
                .optional();
    }

    public Optional<Draft> create(String code, String declaration, long actor) {
        return jdbc.sql("""
                insert into md_form_layout_drafts (entity_code, declaration, created_by, modified_by)
                values (:code, cast(:declaration as jsonb), :actor, :actor)
                on conflict (entity_code) do nothing
                returning entity_code, revision, declaration::text as declaration
                """)
                .param("code", code)
                .param("declaration", declaration)
                .param("actor", actor)
                .query(MdFormStudioRepository::draft)
                .optional();
    }

    public Optional<Draft> update(String code, long expected, String declaration, long actor) {
        return jdbc.sql("""
                update md_form_layout_drafts set declaration = cast(:declaration as jsonb),
                    revision = revision + 1, modified_at = clock_timestamp(), modified_by = :actor
                where entity_code = :code and revision = :expected and revision < 9007199254740991
                returning entity_code, revision, declaration::text as declaration
                """)
                .param("code", code)
                .param("expected", expected)
                .param("declaration", declaration)
                .param("actor", actor)
                .query(MdFormStudioRepository::draft)
                .optional();
    }

    public Optional<StoredVersion> bySource(String code, long revision) {
        return jdbc.sql("select " + VERSION
                        + " from md_form_layout_versions where entity_code = :code and source_revision = :revision")
                .param("code", code)
                .param("revision", revision)
                .query(MdFormStudioRepository::version)
                .optional();
    }

    public Optional<StoredVersion> findVersion(String code, long number) {
        return jdbc.sql("select " + VERSION
                        + " from md_form_layout_versions where entity_code = :code and version = :version")
                .param("code", code)
                .param("version", number)
                .query(MdFormStudioRepository::version)
                .optional();
    }

    public long nextVersion(String code) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from md_form_layout_versions where entity_code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    public StoredVersion insertVersion(
            String code, long number, long revision, String checksum, String artifact, long actor) {
        return jdbc.sql("""
                insert into md_form_layout_versions (entity_code, version, source_revision, checksum, package, created_by)
                values (:code, :version, :revision, :checksum, cast(:package as jsonb), :actor)
                returning id, entity_code, version, source_revision, checksum, package::text as package, created_at
                """)
                .param("code", code)
                .param("version", number)
                .param("revision", revision)
                .param("checksum", checksum)
                .param("package", artifact)
                .param("actor", actor)
                .query(MdFormStudioRepository::version)
                .single();
    }

    public record VersionHeader(long id, long version, long sourceRevision, String checksum, Instant createdAt) {}

    public List<VersionHeader> versions(String code, TimePage page) {
        var statement = jdbc.sql(
                        "select id, version, source_revision, checksum, created_at from md_form_layout_versions where entity_code = :code"
                                + (page.after() == null ? "" : " and (created_at, id) < (:at, :id)")
                                + " order by created_at desc, id desc limit :limit")
                .param("code", code)
                .param("limit", page.limit() + 1);
        if (page.after() != null)
            statement = statement
                    .param("at", Timestamp.from(page.after().at()))
                    .param("id", page.after().id());
        return statement
                .query((rs, row) -> new VersionHeader(
                        rs.getLong("id"),
                        rs.getLong("version"),
                        rs.getLong("source_revision"),
                        rs.getString("checksum"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static Draft draft(ResultSet rs, int row) throws SQLException {
        return new Draft(rs.getString("entity_code"), rs.getLong("revision"), rs.getString("declaration"));
    }

    private static StoredVersion version(ResultSet rs, int row) throws SQLException {
        return new StoredVersion(
                rs.getLong("id"),
                rs.getString("entity_code"),
                rs.getLong("version"),
                rs.getLong("source_revision"),
                rs.getString("checksum"),
                rs.getString("package"),
                rs.getTimestamp("created_at").toInstant());
    }
}
