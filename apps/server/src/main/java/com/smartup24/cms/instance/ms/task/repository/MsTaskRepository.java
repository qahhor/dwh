package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the task's own resources need of the task row, which the general runtime keeps ({@code ms.tasks}, ADR-0032,
 * 8): whether a viewer sees it, and its title for a notification.
 */
@Repository
public class MsTaskRepository {

    private final JdbcClient jdbcClient;

    public MsTaskRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The title of the task if the predicate lets the viewer see it (its alias is {@code t}, ADR-0013). */
    public Optional<String> visibleTitle(long taskId, ScopeFilter scope) {
        var query = jdbcClient
                .sql("select t.title from ms_tasks t where t.id = :id" + scope.sql())
                .param("id", taskId);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(String.class).optional();
    }
}
