package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bounded role ACLs for preferences; the resource identifiers are server constants (ADR-0036). */
@Repository
public class MdSharingRepository {
    public enum Resource {
        REPORT("md_list_view_roles", "view_id"),
        DASHBOARD("md_dashboard_roles", "dashboard_id");
        private final String table;
        private final String column;

        Resource(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public String table() {
            return table;
        }
    }

    public record RoleRow(SharingRole value, Instant modifiedAt) {}

    private final JdbcClient jdbc;

    public MdSharingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> roles(Resource resource, long id) {
        return jdbc.sql("select role_id from " + resource.table + " where " + resource.column
                        + " = :id order by role_id")
                .param("id", id)
                .query(Long.class)
                .list();
    }

    public boolean active(List<Long> roles) {
        return roles.isEmpty()
                || jdbc.sql("select count(*) from md_roles where id in (:ids) and state = 'A'")
                                .param("ids", roles)
                                .query(Long.class)
                                .single()
                        == roles.size();
    }

    public void replace(Resource resource, long id, List<Long> roles) {
        jdbc.sql("delete from " + resource.table + " where " + resource.column + " = :id")
                .param("id", id)
                .update();
        for (long role : roles) {
            jdbc.sql("insert into " + resource.table + " (" + resource.column + ", role_id) values (:id, :role)")
                    .param("id", id)
                    .param("role", role)
                    .update();
        }
    }

    public List<RoleRow> page(TimePage page) {
        var after = page.after();
        return jdbc.sql("select id, name, modified_at from md_roles where state = 'A' "
                        + "and (cast(:at as timestamptz) is null or (modified_at, id) < (:at, :id)) "
                        + "order by modified_at desc, id desc limit :limit")
                .param("at", after == null ? null : Timestamp.from(after.at()))
                .param("id", after == null ? 0 : after.id())
                .param("limit", page.limit() + 1)
                .query((row, index) -> new RoleRow(
                        new SharingRole(row.getLong("id"), row.getString("name")),
                        row.getTimestamp("modified_at").toInstant()))
                .list();
    }
}
