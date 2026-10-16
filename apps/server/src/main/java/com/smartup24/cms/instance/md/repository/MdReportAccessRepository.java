package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.repository.MdListViewRepository.ListView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Definition ACL is separate from data scope and is read from active roles for every request (ADR-0036). */
@Repository
public class MdReportAccessRepository {
    private static final String COLUMNS = "v.id, v.user_id, v.list_code, v.kind, v.name, v.state::text as state, "
            + "v.is_default, v.lock_version, v.modified_at, "
            + "exists (select 1 from md_list_view_roles shares where shares.view_id = v.id) as shared";
    private static final String READABLE = "(v.user_id = :u or exists (select 1 from md_list_view_roles a "
            + "join md_user_roles ur on ur.role_id = a.role_id join md_roles r on r.id = a.role_id "
            + "and r.state = 'A' where a.view_id = v.id and ur.user_id = :u))";
    private final JdbcClient jdbc;

    public record ReportRow(ListView view, long ownerId, boolean shared) {}

    public MdReportAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReportRow> page(long userId, @Nullable String listCode, TimePage page) {
        var after = page.after();
        return jdbc.sql("select " + COLUMNS + " from md_list_views v where v.kind in ('report', 'widget') and "
                        + READABLE + " and (cast(:list as text) is null or v.list_code = :list)"
                        + " and (cast(:at as timestamptz) is null or (v.modified_at, v.id) < (:at, :id))"
                        + " order by v.modified_at desc, v.id desc limit :limit")
                .param("u", userId)
                .param("list", listCode)
                .param("at", after == null ? null : Timestamp.from(after.at()))
                .param("id", after == null ? 0 : after.id())
                .param("limit", page.limit() + 1)
                .query(this::map)
                .list();
    }

    public Optional<ReportRow> readable(long userId, long id) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views v where v.id = :id "
                        + "and v.kind in ('report', 'widget') and " + READABLE)
                .param("u", userId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<ReportRow> owned(long userId, String listCode, long id, boolean lock) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views v where v.id = :id and v.user_id = :u "
                        + "and v.list_code = :list and v.kind in ('report', 'widget')"
                        + (lock ? " for update of v" : ""))
                .param("u", userId)
                .param("id", id)
                .param("list", listCode)
                .query(this::map)
                .optional();
    }

    public long touch(long userId, long id, long expected) {
        return jdbc.sql("update md_list_views set lock_version = lock_version + 1, modified_at = clock_timestamp() "
                        + "where id = :id and user_id = :u and lock_version = :revision returning lock_version")
                .param("u", userId)
                .param("id", id)
                .param("revision", expected)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    private ReportRow map(ResultSet row, int index) throws SQLException {
        return new ReportRow(
                new ListView(
                        row.getLong("id"),
                        row.getString("list_code"),
                        row.getString("kind"),
                        row.getString("name"),
                        row.getString("state"),
                        row.getBoolean("is_default"),
                        row.getInt("lock_version"),
                        row.getTimestamp("modified_at").toInstant()),
                row.getLong("user_id"),
                row.getBoolean("shared"));
    }
}
