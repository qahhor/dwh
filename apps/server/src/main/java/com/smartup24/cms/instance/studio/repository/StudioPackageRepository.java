package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The imports of solution packages and what applied packages created without a code of their own (ADR-0047). The
 * package and the outcome of its items are JSON the service writes through its columns; an applied import changes no
 * more, a check that was never applied expires (retention).
 */
@Repository
public class StudioPackageRepository {
    private static final String SUMMARY = """
            select id, code, version, title, checksum, source, suffix, items, status, mapping, created_by,
                   created_at, checked_at, applied_by, applied_at
            from studio_package_imports
            """;

    private final JdbcClient jdbc;

    /** An import as the history lists it; the package itself is read with {@link #packageOf(long)}. */
    public record ImportRow(
            long id,
            String code,
            String version,
            String title,
            String checksum,
            String source,
            @Nullable String suffix,
            String items,
            String status,
            String mapping,
            long createdBy,
            Instant createdAt,
            Instant checkedAt,
            @Nullable Long appliedBy,
            @Nullable Instant appliedAt) {}

    /** What an applied package created for one of its items, and the item's checksum then. */
    public record ObjectRow(long objectId, String checksum) {}

    public StudioPackageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(
            String code,
            String version,
            String title,
            String checksum,
            String source,
            @Nullable String suffix,
            String pack,
            String items,
            long userId) {
        Long id = jdbc.sql("""
                        insert into studio_package_imports
                            (code, version, title, checksum, source, suffix, package, items, created_by)
                        values (:code, :version, cast(:title as jsonb), :checksum, :source, :suffix,
                                cast(:pack as jsonb), cast(:items as jsonb), :user)
                        returning id
                        """)
                .param("code", code)
                .param("version", version)
                .param("title", title)
                .param("checksum", checksum)
                .param("source", source)
                .param("suffix", suffix)
                .param("pack", pack)
                .param("items", items)
                .param("user", userId)
                .query(Long.class)
                .single();
        return id == null ? 0 : id;
    }

    public Optional<ImportRow> find(long id) {
        return jdbc.sql(SUMMARY + " where id = :id")
                .param("id", id)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /** The import under a row lock: two applications of one import take turns. */
    public Optional<ImportRow> lock(long id) {
        return jdbc.sql(SUMMARY + " where id = :id for update")
                .param("id", id)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /** The package of the import, as it was verified on upload. */
    public String packageOf(long id) {
        return jdbc.sql("select package::text from studio_package_imports where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    /** Imports, the newest first; {@code limit + 1} rows are read to tell whether more follow. */
    public List<ImportRow> page(TimePage page) {
        TimePage.Position after = page.after();
        String where = after == null ? "" : " where (created_at, id) < (:at, :id)";
        var statement = jdbc.sql(SUMMARY + where + " order by created_at desc, id desc limit :limit")
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    /** The outcome of a check and the mapping it was made with. */
    public void checked(long id, String items, String mapping) {
        jdbc.sql("""
                        update studio_package_imports
                        set items = cast(:items as jsonb), mapping = cast(:mapping as jsonb),
                            checked_at = clock_timestamp()
                        where id = :id and status = 'preview'
                        """)
                .param("id", id)
                .param("items", items)
                .param("mapping", mapping)
                .update();
    }

    /** The import was applied with the mapping; it changes no more. */
    public void applied(long id, String items, String mapping, long userId) {
        jdbc.sql("""
                        update studio_package_imports
                        set items = cast(:items as jsonb), mapping = cast(:mapping as jsonb), status = 'applied',
                            checked_at = clock_timestamp(), applied_by = :user, applied_at = clock_timestamp()
                        where id = :id and status = 'preview'
                        """)
                .param("id", id)
                .param("items", items)
                .param("mapping", mapping)
                .param("user", userId)
                .update();
    }

    public Optional<ObjectRow> object(String scope, String kind, String key) {
        return jdbc.sql("""
                        select object_id, checksum from studio_package_objects
                        where package_code = :scope and kind = :kind and item_key = :key
                        """)
                .param("scope", scope)
                .param("kind", kind)
                .param("key", key)
                .query((rs, row) -> new ObjectRow(rs.getLong("object_id"), rs.getString("checksum")))
                .optional();
    }

    /** Remembers what the item created or changed, replacing what an earlier application remembered. */
    public void remember(String scope, String kind, String key, long objectId, String checksum, long importId) {
        jdbc.sql("""
                        insert into studio_package_objects (package_code, kind, item_key, object_id, checksum, import_id)
                        values (:scope, :kind, :key, :object, :checksum, :import)
                        on conflict (package_code, kind, item_key) do update
                        set object_id = excluded.object_id, checksum = excluded.checksum,
                            import_id = excluded.import_id, modified_at = clock_timestamp()
                        """)
                .param("scope", scope)
                .param("kind", kind)
                .param("key", key)
                .param("object", objectId)
                .param("checksum", checksum)
                .param("import", importId)
                .update();
    }

    private static ImportRow row(ResultSet rs) throws SQLException {
        long appliedBy = rs.getLong("applied_by");
        Long applier = rs.wasNull() ? null : appliedBy;
        return new ImportRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("version"),
                rs.getString("title"),
                rs.getString("checksum"),
                rs.getString("source"),
                rs.getString("suffix"),
                rs.getString("items"),
                rs.getString("status"),
                rs.getString("mapping"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("checked_at").toInstant(),
                applier,
                instant(rs.getTimestamp("applied_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
