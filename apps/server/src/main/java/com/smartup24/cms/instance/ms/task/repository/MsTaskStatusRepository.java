package com.smartup24.cms.instance.ms.task.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The task statuses as the task services read them. The statuses themselves are an entity on the general runtime
 * ({@code ms.task_statuses}, ADR-0032, 8): created, changed, moved, archived and deleted there.
 */
@Repository
public class MsTaskStatusRepository {

    private static final String COLUMNS = "id, code, name, color, sort_order, is_terminal, revision";

    private static final RowMapper<StatusRecord> ROW = (rs, rowNum) -> new StatusRecord(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("color"),
            rs.getInt("sort_order"),
            rs.getBoolean("is_terminal"),
            rs.getLong("revision"));

    private final JdbcClient jdbcClient;

    public MsTaskStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The statuses in use, in their order. */
    public List<StatusRecord> listStatuses() {
        return jdbcClient
                .sql("select " + COLUMNS + " from ms_task_statuses where archived_at is null order by sort_order, id")
                .query(ROW)
                .list();
    }

    public Optional<StatusRecord> findById(Long id) {
        return jdbcClient
                .sql("select " + COLUMNS + " from ms_task_statuses where id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** The status in use with this code. */
    public Optional<StatusRecord> findByCode(String code) {
        return jdbcClient
                .sql("select " + COLUMNS + " from ms_task_statuses where code = :code and archived_at is null")
                .param("code", code)
                .query(ROW)
                .optional();
    }

    public record StatusRecord(
            Long id, String code, String name, String color, int sortOrder, boolean isTerminal, long revision) {}
}
