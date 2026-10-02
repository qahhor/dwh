package com.smartup24.cms.instance.ms.task.repository;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The task statuses as the task's action and notifications read them, by code. The statuses themselves are an entity
 * on the general runtime ({@code ms.task_statuses}, ADR-0032, 8): created, changed, moved, archived and deleted there.
 */
@Repository
public class MsTaskStatusRepository {

    private static final String COLUMNS = "id, code, name, is_terminal";

    private static final RowMapper<StatusRecord> ROW = (rs, rowNum) -> new StatusRecord(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_terminal"));

    private final JdbcClient jdbcClient;

    public MsTaskStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The status in use with this code: one a task may move to. */
    public Optional<StatusRecord> findByCode(String code) {
        return jdbcClient
                .sql("select " + COLUMNS + " from ms_task_statuses where code = :code and archived_at is null")
                .param("code", code)
                .query(ROW)
                .optional();
    }

    /** The status with this code, an archived one too: the one a task keeps. */
    public Optional<StatusRecord> findAnyByCode(String code) {
        return jdbcClient
                .sql("select " + COLUMNS + " from ms_task_statuses where code = :code"
                        + " order by archived_at nulls first limit 1")
                .param("code", code)
                .query(ROW)
                .optional();
    }

    public record StatusRecord(long id, String code, String name, boolean isTerminal) {}
}
