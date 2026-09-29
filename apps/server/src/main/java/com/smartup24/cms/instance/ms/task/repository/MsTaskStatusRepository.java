package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.web.Revisions;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MsTaskStatusRepository {

    private final JdbcClient jdbcClient;

    public MsTaskStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void initDefaultStatusesIfEmpty() {
        int count = jdbcClient
                .sql("select count(*) from ms_task_statuses")
                .query(Integer.class)
                .single();
        if (count == 0) {
            jdbcClient.sql("""
                    insert into ms_task_statuses (pcode, name, color, order_no, is_terminal) values
                    ('new', 'Новая', '#3b82f6', 10, false),
                    ('in_progress', 'В работе', '#eab308', 20, false),
                    ('done', 'Выполнена', '#22c55e', 30, true),
                    ('cancelled', 'Отменена', '#ef4444', 40, true)
                    """).update();
        }
    }

    public List<StatusRecord> listStatuses() {
        return jdbcClient
                .sql("""
                select id, pcode, name, color, order_no, is_terminal, revision
                from ms_task_statuses
                order by order_no asc, id asc
                """)
                .query((rs, rowNum) -> new StatusRecord(
                        rs.getLong("id"),
                        rs.getString("pcode"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_terminal"),
                        rs.getLong("revision")))
                .list();
    }

    public Optional<StatusRecord> findById(Long id) {
        return jdbcClient
                .sql("""
                select id, pcode, name, color, order_no, is_terminal, revision
                from ms_task_statuses
                where id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new StatusRecord(
                        rs.getLong("id"),
                        rs.getString("pcode"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_terminal"),
                        rs.getLong("revision")))
                .optional();
    }

    public Optional<StatusRecord> findByPcode(String pcode) {
        return jdbcClient
                .sql("""
                select id, pcode, name, color, order_no, is_terminal, revision
                from ms_task_statuses
                where pcode = :pcode
                """)
                .param("pcode", pcode)
                .query((rs, rowNum) -> new StatusRecord(
                        rs.getLong("id"),
                        rs.getString("pcode"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_terminal"),
                        rs.getLong("revision")))
                .optional();
    }

    public StatusRecord create(String pcode, String name, String color, int orderNo, boolean isTerminal) {
        return jdbcClient
                .sql("""
                insert into ms_task_statuses (pcode, name, color, order_no, is_terminal)
                values (:pcode, :name, :color, :orderNo, :isTerminal)
                returning id, pcode, name, color, order_no, is_terminal, revision
                """)
                .param("pcode", pcode != null && !pcode.isBlank() ? pcode.trim().toLowerCase() : null)
                .param("name", name.trim())
                .param("color", color != null && !color.isBlank() ? color.trim() : "#6366f1")
                .param("orderNo", orderNo)
                .param("isTerminal", isTerminal)
                .query((rs, rowNum) -> new StatusRecord(
                        rs.getLong("id"),
                        rs.getString("pcode"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_terminal"),
                        rs.getLong("revision")))
                .single();
    }

    public long update(Long id, String name, String color, Integer orderNo, Boolean isTerminal, long expectedRevision) {
        return jdbcClient
                .sql("""
                update ms_task_statuses
                set name = coalesce(:name, name),
                    color = coalesce(:color, color),
                    order_no = coalesce(:orderNo, order_no),
                    is_terminal = coalesce(:isTerminal, is_terminal),
                    revision = revision + 1
                where id = :id and revision = :expectedRevision
                returning revision
                """)
                .param("id", id)
                .param("name", name != null && !name.isBlank() ? name.trim() : null)
                .param("color", color != null && !color.isBlank() ? color.trim() : null)
                .param("orderNo", orderNo)
                .param("isTerminal", isTerminal)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public boolean delete(Long id) {
        // Only delete if no tasks are using this status and it is not a base system status
        int taskCount = jdbcClient
                .sql("select count(*) from ms_tasks where status_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
        if (taskCount > 0) {
            return false;
        }

        int rows = jdbcClient
                .sql("delete from ms_task_statuses where id = :id and pcode is null")
                .param("id", id)
                .update();
        return rows > 0;
    }

    public void reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return;
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbcClient
                    .sql("update ms_task_statuses set order_no = :orderNo where id = :id")
                    .param("orderNo", (i + 1) * 10)
                    .param("id", orderedIds.get(i))
                    .update();
        }
    }

    public record StatusRecord(
            Long id, String pcode, String name, String color, int orderNo, boolean isTerminal, long revision) {}
}
