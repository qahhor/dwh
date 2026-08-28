package com.greenwhite.dwh.instance.ms.task.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MsTaskStatusRepository {

    private final JdbcClient jdbcClient;

    public MsTaskStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void initDefaultStatusesIfEmpty() {
        int count = jdbcClient.sql("select count(*) from ms_task_statuses").query(Integer.class).single();
        if (count == 0) {
            jdbcClient.sql("""
                    insert into ms_task_statuses (pcode, name, color, order_no, is_terminal) values
                    ('new', 'РќРѕРІР°СЏ', '#3b82f6', 10, false),
                    ('in_progress', 'Р’ СЂР°Р±РѕС‚Рµ', '#eab308', 20, false),
                    ('done', 'Р’С‹РїРѕР»РЅРµРЅР°', '#22c55e', 30, true),
                    ('cancelled', 'РћС‚РјРµРЅРµРЅР°', '#ef4444', 40, true)
                    """).update();
        }
    }

    public List<StatusRecord> listStatuses() {
        return jdbcClient.sql("""
                select id, pcode, name, color, order_no, is_terminal
                from ms_task_statuses
                order by order_no asc
                """)
                .query((rs, rowNum) -> new StatusRecord(
                        rs.getLong("id"),
                        rs.getString("pcode"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getInt("order_no"),
                        rs.getBoolean("is_terminal")
                ))
                .list();
    }

    public Optional<StatusRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, pcode, name, color, order_no, is_terminal
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
                        rs.getBoolean("is_terminal")
                ))
                .optional();
    }

    public Optional<StatusRecord> findByPcode(String pcode) {
        return jdbcClient.sql("""
                select id, pcode, name, color, order_no, is_terminal
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
                        rs.getBoolean("is_terminal")
                ))
                .optional();
    }

    public record StatusRecord(
            Long id,
            String pcode,
            String name,
            String color,
            int orderNo,
            boolean isTerminal
    ) {}
}
