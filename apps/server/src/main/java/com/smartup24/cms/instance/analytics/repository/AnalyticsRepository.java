package com.smartup24.cms.instance.analytics.repository;

import com.smartup24.cms.instance.analytics.api.AnalyticsSummaryDto;
import com.smartup24.cms.instance.analytics.api.ProjectDistributionDto;
import com.smartup24.cms.instance.analytics.api.TrendDataPointDto;
import com.smartup24.cms.instance.analytics.api.UserWorkloadDto;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The dashboard figures over the published views (ADR-0026), each computed only over the rows the viewer may see
 * (ADR-0013, 2.5): tasks through the task predicate (alias {@code t}), projects through the project predicate (alias
 * {@code p}), users through the user predicate (column {@code u.id}).
 */
@Repository
public class AnalyticsRepository {

    /**
     * The viewer's row restrictions: {@code tasks} for alias {@code t}, {@code projects} for alias {@code p},
     * {@code users} for column {@code u.id}. All of them bind the same viewer, or none for the rule ALL.
     */
    public record Scope(ScopeFilter tasks, ScopeFilter projects, ScopeFilter users) {

        /** The rule ALL: the figures of the whole installation. */
        public static Scope unrestricted() {
            return new Scope(ScopeFilter.unrestricted(), ScopeFilter.unrestricted(), ScopeFilter.unrestricted());
        }
    }

    private final JdbcClient jdbcClient;

    public AnalyticsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The tasks the viewer may see, as the CTE {@code visible_tasks} every figure reads. */
    private static String visibleTasks(Scope scope) {
        return """
                visible_tasks as (
                    select t.id, t.project_id, t.status_code, t.end_time, t.resolved_time, t.created_at, t.modified_at
                    from ms_task_pub_tasks t
                    where 1=1
                """ + scope.tasks().sql() + "\n)\n";
    }

    private static StatementSpec bind(StatementSpec statement, Scope scope) {
        for (ScopeFilter filter : List.of(scope.tasks(), scope.projects(), scope.users())) {
            if (filter.bindsUserId()) {
                return statement.param("scopeUserId", filter.userId());
            }
        }
        return statement;
    }

    public AnalyticsSummaryDto getSummary(Scope scope) {
        String sql = "with " + visibleTasks(scope) + """
                , task_metrics as (
                    select
                        count(*) as total_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = false) as active_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = true) as completed_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = false and t.end_time is not null and t.end_time < now()) as overdue_tasks,
                        count(*) filter (where t.created_at >= now() - interval '7 days') as created_7d,
                        count(*) filter (where t.resolved_time >= now() - interval '7 days' or (s.is_terminal = true and t.modified_at >= now() - interval '7 days')) as completed_7d
                    from visible_tasks t
                    left join ms_task_pub_statuses s on s.code = t.status_code
                ),
                project_metrics as (
                    select count(*) as active_projects from ms_task_pub_projects p where not p.archived
                """ + scope.projects().sql() + """
                ),
                user_metrics as (
                    select count(*) as active_users from md_pub_users u where u.state = 'A'
                """
                + scope.users().sql() + """
                )
                select
                    tm.total_tasks,
                    tm.active_tasks,
                    tm.completed_tasks,
                    tm.overdue_tasks,
                    case
                        when tm.total_tasks > 0 then round((tm.completed_tasks::numeric / tm.total_tasks::numeric) * 100, 1)
                        else 0.0
                    end as completion_rate,
                    tm.created_7d,
                    tm.completed_7d,
                    pm.active_projects,
                    um.active_users
                from task_metrics tm
                cross join project_metrics pm
                cross join user_metrics um
                """;
        return bind(jdbcClient.sql(sql), scope)
                .query((rs, rowNum) -> new AnalyticsSummaryDto(
                        rs.getLong("total_tasks"),
                        rs.getLong("active_tasks"),
                        rs.getLong("completed_tasks"),
                        rs.getLong("overdue_tasks"),
                        rs.getDouble("completion_rate"),
                        rs.getLong("created_7d"),
                        rs.getLong("completed_7d"),
                        rs.getLong("active_projects"),
                        rs.getLong("active_users")))
                .single();
    }

    public List<TrendDataPointDto> getTrends(int days, Scope scope) {
        int safeDays = Math.max(1, Math.min(days, 365));
        String sql = "with " + visibleTasks(scope) + """
                , calendar as (
                    select generate_series(
                        date_trunc('day', now()) - (:days - 1) * interval '1 day',
                        date_trunc('day', now()),
                        interval '1 day'
                    )::date as day
                ),
                created as (
                    select date_trunc('day', created_at)::date as day, count(*) as count
                    from visible_tasks
                    where created_at >= date_trunc('day', now()) - (:days - 1) * interval '1 day'
                    group by 1
                ),
                completed as (
                    select date_trunc('day', coalesce(resolved_time, modified_at))::date as day, count(*) as count
                    from visible_tasks t
                    join ms_task_pub_statuses s on s.code = t.status_code and s.is_terminal = true
                    where coalesce(resolved_time, modified_at) >= date_trunc('day', now()) - (:days - 1) * interval '1 day'
                    group by 1
                )
                select
                    to_char(c.day, 'YYYY-MM-DD') as date_str,
                    coalesce(cr.count, 0) as created_count,
                    coalesce(cp.count, 0) as completed_count
                from calendar c
                left join created cr on cr.day = c.day
                left join completed cp on cp.day = c.day
                order by c.day asc
                """;
        return bind(jdbcClient.sql(sql), scope)
                .param("days", safeDays)
                .query((rs, rowNum) -> new TrendDataPointDto(
                        rs.getString("date_str"), rs.getLong("created_count"), rs.getLong("completed_count")))
                .list();
    }

    public List<ProjectDistributionDto> getProjectDistribution(Scope scope) {
        String sql = "with " + visibleTasks(scope) + """
                select
                    p.id as project_id,
                    p.name as project_name,
                    count(t.id) as total_tasks,
                    count(t.id) filter (where coalesce(s.is_terminal, false) = false) as active_tasks,
                    count(t.id) filter (where coalesce(s.is_terminal, false) = true) as completed_tasks,
                    case
                        when count(t.id) > 0 then round((count(t.id) filter (where coalesce(s.is_terminal, false) = true)::numeric / count(t.id)::numeric) * 100, 1)
                        else 0.0
                    end as progress_percent
                from ms_task_pub_projects p
                left join visible_tasks t on t.project_id = p.id
                left join ms_task_pub_statuses s on s.code = t.status_code
                where not p.archived
                """ + scope.projects().sql() + """

                group by p.id, p.name
                order by total_tasks desc, p.name asc
                limit 15
                """;
        return bind(jdbcClient.sql(sql), scope)
                .query((rs, rowNum) -> new ProjectDistributionDto(
                        rs.getLong("project_id"),
                        rs.getString("project_name"),
                        rs.getLong("total_tasks"),
                        rs.getLong("active_tasks"),
                        rs.getLong("completed_tasks"),
                        rs.getDouble("progress_percent")))
                .list();
    }

    public List<UserWorkloadDto> getUserWorkload(Scope scope) {
        String sql = "with " + visibleTasks(scope) + """
                select
                    u.id as user_id,
                    u.name as user_name,
                    u.login as user_login,
                    count(distinct t.id) as assigned_tasks,
                    count(distinct t.id) filter (where coalesce(s.is_terminal, false) = true) as completed_tasks
                from md_pub_users u
                left join ms_task_pub_members tm on tm.user_id = u.id
                left join visible_tasks t on t.id = tm.task_id
                left join ms_task_pub_statuses s on s.code = t.status_code
                where u.state = 'A'
                """ + scope.users().sql() + """

                group by u.id, u.name, u.login
                order by assigned_tasks desc, u.name asc
                limit 15
                """;
        return bind(jdbcClient.sql(sql), scope)
                .query((rs, rowNum) -> new UserWorkloadDto(
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getString("user_login"),
                        rs.getLong("assigned_tasks"),
                        rs.getLong("completed_tasks")))
                .list();
    }
}
