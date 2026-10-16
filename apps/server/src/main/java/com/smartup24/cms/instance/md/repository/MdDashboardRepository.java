package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.web.Revisions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Named dashboard preferences with ownership, live role ACL and optimistic revisions (ADR-0036). */
@Repository
public class MdDashboardRepository {
    private static final String COLUMNS =
            "d.id, d.user_id, d.name, d.widgets::text as widgets, d.revision, d.modified_at, "
                    + "exists (select 1 from md_dashboard_roles shares where shares.dashboard_id = d.id) as shared";
    private static final String READABLE = "(d.user_id = :u or exists (select 1 from md_dashboard_roles a "
            + "join md_user_roles ur on ur.role_id = a.role_id join md_roles r on r.id = a.role_id "
            + "and r.state = 'A' where a.dashboard_id = d.id and ur.user_id = :u))";
    private final JdbcClient jdbc;

    public record DashboardRow(
            long id,
            long ownerId,
            String name,
            String widgetsJson,
            long revision,
            Instant modifiedAt,
            boolean shared) {}

    public MdDashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DashboardRow> page(long userId, TimePage page) {
        var after = page.after();
        return jdbc.sql("select " + COLUMNS + " from md_dashboards d where " + READABLE
                        + " and (cast(:at as timestamptz) is null or (d.modified_at, d.id) < (:at, :id))"
                        + " order by d.modified_at desc, d.id desc limit :limit")
                .param("u", userId)
                .param("at", after == null ? null : Timestamp.from(after.at()))
                .param("id", after == null ? 0 : after.id())
                .param("limit", page.limit() + 1)
                .query(this::map)
                .list();
    }

    public Optional<DashboardRow> readable(long userId, long id) {
        return jdbc.sql("select " + COLUMNS + " from md_dashboards d where d.id = :id and " + READABLE)
                .param("u", userId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<DashboardRow> owned(long userId, long id, boolean lock) {
        return jdbc.sql("select " + COLUMNS + " from md_dashboards d where d.id = :id and d.user_id = :u"
                        + (lock ? " for update of d" : ""))
                .param("u", userId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public void lockOwner(long userId) {
        jdbc.sql("select id from md_users where id = :u for update")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    public long count(long userId) {
        return jdbc.sql("select count(*) from md_dashboards where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    public long insert(long userId, String name, String widgets) {
        return jdbc.sql(
                        "insert into md_dashboards (user_id, name, widgets) values (:u, :name, cast(:widgets as jsonb)) returning id")
                .param("u", userId)
                .param("name", name)
                .param("widgets", widgets)
                .query(Long.class)
                .single();
    }

    public void update(long userId, long id, long expected, String name, String widgets) {
        int changed = jdbc.sql("update md_dashboards set name = :name, widgets = cast(:widgets as jsonb), "
                        + "revision = revision + 1, modified_at = clock_timestamp() "
                        + "where id = :id and user_id = :u and revision = :revision")
                .param("u", userId)
                .param("id", id)
                .param("revision", expected)
                .param("name", name)
                .param("widgets", widgets)
                .update();
        if (changed == 0) throw Revisions.conflict();
    }

    public long touch(long userId, long id, long expected) {
        return jdbc.sql("update md_dashboards set revision = revision + 1, modified_at = clock_timestamp() "
                        + "where id = :id and user_id = :u and revision = :revision returning revision")
                .param("u", userId)
                .param("id", id)
                .param("revision", expected)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public void delete(long userId, long id, long expected) {
        int changed = jdbc.sql("delete from md_dashboards where id = :id and user_id = :u and revision = :revision")
                .param("u", userId)
                .param("id", id)
                .param("revision", expected)
                .update();
        if (changed == 0) throw Revisions.conflict();
    }

    private DashboardRow map(ResultSet row, int index) throws SQLException {
        return new DashboardRow(
                row.getLong("id"),
                row.getLong("user_id"),
                row.getString("name"),
                row.getString("widgets"),
                row.getLong("revision"),
                row.getTimestamp("modified_at").toInstant(),
                row.getBoolean("shared"));
    }
}
