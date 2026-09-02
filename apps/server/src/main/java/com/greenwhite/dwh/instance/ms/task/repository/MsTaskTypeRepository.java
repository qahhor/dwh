package com.greenwhite.dwh.instance.ms.task.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MsTaskTypeRepository {

    private final JdbcClient jdbcClient;

    public MsTaskTypeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void initDefaultTypesIfEmpty() {
        jdbcClient.sql("""
                insert into ms_task_types (code, name, icon, color, order_no, is_system)
                values
                    ('task', 'Задача', 'task_alt', '#6366f1', 10, true),
                    ('bug', 'Ошибка', 'bug_report', '#ef4444', 20, true),
                    ('feature', 'Улучшение', 'bolt', '#f59e0b', 30, true),
                    ('research', 'Исследование', 'science', '#8b5cf6', 40, true)
                on conflict (code) do nothing
                """).update();
    }

    public List<TypeRecord> listTypes() {
        return jdbcClient.sql("""
                select id, code, name, icon, color, order_no, is_system, created_at
                from ms_task_types
                order by order_no asc, id asc
                """)
                .query((rs, rowNum) -> new TypeRecord(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("icon"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public Optional<TypeRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, code, name, icon, color, order_no, is_system, created_at
                from ms_task_types
                where id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new TypeRecord(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("icon"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .optional();
    }

    public Optional<TypeRecord> findByCode(String code) {
        return jdbcClient.sql("""
                select id, code, name, icon, color, order_no, is_system, created_at
                from ms_task_types
                where code = :code
                """)
                .param("code", code)
                .query((rs, rowNum) -> new TypeRecord(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("icon"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .optional();
    }

    public TypeRecord create(String code, String name, String icon, String color, int orderNo) {
        return jdbcClient.sql("""
                insert into ms_task_types (code, name, icon, color, order_no, is_system, created_at)
                values (:code, :name, :icon, :color, :orderNo, false, now())
                returning id, code, name, icon, color, order_no, is_system, created_at
                """)
                .param("code", code.trim().toLowerCase())
                .param("name", name.trim())
                .param("icon", icon != null && !icon.isBlank() ? icon.trim() : "task_alt")
                .param("color", color != null && !color.isBlank() ? color.trim() : "#6366f1")
                .param("orderNo", orderNo)
                .query((rs, rowNum) -> new TypeRecord(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("icon"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .single();
    }

    public void update(Long id, String name, String icon, String color, Integer orderNo) {
        jdbcClient.sql("""
                update ms_task_types
                set name = coalesce(:name, name),
                    icon = coalesce(:icon, icon),
                    color = coalesce(:color, color),
                    order_no = coalesce(:orderNo, order_no)
                where id = :id
                """)
                .param("id", id)
                .param("name", name != null && !name.isBlank() ? name.trim() : null)
                .param("icon", icon != null && !icon.isBlank() ? icon.trim() : null)
                .param("color", color != null && !color.isBlank() ? color.trim() : null)
                .param("orderNo", orderNo)
                .update();
    }

    public boolean delete(Long id) {
        int rows = jdbcClient.sql("delete from ms_task_types where id = :id and is_system = false")
                .param("id", id)
                .update();
        return rows > 0;
    }

    public void reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return;
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbcClient.sql("update ms_task_types set order_no = :orderNo where id = :id")
                    .param("orderNo", (i + 1) * 10)
                    .param("id", orderedIds.get(i))
                    .update();
        }
    }


    public record TypeRecord(
            Long id,
            String code,
            String name,
            String icon,
            String color,
            int orderNo,
            boolean isSystem,
            Instant createdAt
    ) {}
}
