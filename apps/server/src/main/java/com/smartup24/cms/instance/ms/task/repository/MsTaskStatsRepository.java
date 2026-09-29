package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads over many tasks at once: the progress of each project and the deadlines coming up. */
@Repository
public class MsTaskStatsRepository {

    private final JdbcClient jdbcClient;

    public MsTaskStatsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProjectTaskStats> getProjectTaskStats() {
        return getProjectTaskStats(ScopeFilter.unrestricted());
    }

    public List<ProjectTaskStats> getProjectTaskStats(ScopeFilter scope) {
        String sql = """
                select p.id as project_id,
                       count(t.id) as total_tasks,
                       count(t.id) filter (where s.is_terminal = false) as active_tasks,
                       count(t.id) filter (where s.is_terminal = true) as done_tasks
                from ms_task_projects p
                left join ms_tasks t on t.project_id = p.id
                """ + scope.sql() + """
                left join ms_task_statuses s on s.id = t.status_id
                group by p.id
                """;
        var query = jdbcClient.sql(sql);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query((rs, rowNum) -> new ProjectTaskStats(
                        rs.getLong("project_id"),
                        rs.getInt("total_tasks"),
                        rs.getInt("active_tasks"),
                        rs.getInt("done_tasks")))
                .list();
    }

    public record ProjectTaskStats(Long projectId, int totalTasks, int activeTasks, int doneTasks) {}

    public record TaskDeadlineCandidate(long taskId, String title, long userId) {}

    public List<TaskDeadlineCandidate> findUpcomingDeadlines(Duration window) {
        return jdbcClient
                .sql("""
                select distinct t.id as task_id, t.title, tm.user_id
                from ms_tasks t
                join ms_task_statuses s on s.id = t.status_id and s.is_terminal = false
                join ms_task_members tm on tm.task_id = t.id and tm.involve_kind in ('R', 'E')
                where t.end_time is not null
                  and t.end_time > now()
                  and t.end_time <= now() + cast(:windowSeconds || ' seconds' as interval)
                """)
                .param("windowSeconds", window.toSeconds())
                .query((rs, rowNum) ->
                        new TaskDeadlineCandidate(rs.getLong("task_id"), rs.getString("title"), rs.getLong("user_id")))
                .list();
    }
}
