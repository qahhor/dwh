package com.greenwhite.dwh.instance.analytics.repository;

import com.greenwhite.dwh.instance.analytics.dto.AnalyticsSummaryDto;
import com.greenwhite.dwh.instance.analytics.dto.ProjectDistributionDto;
import com.greenwhite.dwh.instance.analytics.dto.TrendDataPointDto;
import com.greenwhite.dwh.instance.analytics.dto.UserWorkloadDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AnalyticsRepository {

    private final JdbcClient jdbcClient;

    public AnalyticsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AnalyticsSummaryDto getSummary() {
        return jdbcClient.sql("""
                with task_metrics as (
                    select
                        count(*) as total_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = false) as active_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = true) as completed_tasks,
                        count(*) filter (where coalesce(s.is_terminal, false) = false and t.end_time is not null and t.end_time < now()) as overdue_tasks,
                        count(*) filter (where t.created_at >= now() - interval '7 days') as created_7d,
                        count(*) filter (where t.resolved_time >= now() - interval '7 days' or (s.is_terminal = true and t.modified_at >= now() - interval '7 days')) as completed_7d
                    from ms_tasks t
                    left join ms_task_statuses s on s.id = t.status_id
                ),
                project_metrics as (
                    select count(*) as active_projects from ms_task_projects where state = 'A'
                ),
                user_metrics as (
                    select count(*) as active_users from md_users where state = 'A'
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
                """)
                .query((rs, rowNum) -> new AnalyticsSummaryDto(
                        rs.getLong("total_tasks"),
                        rs.getLong("active_tasks"),
                        rs.getLong("completed_tasks"),
                        rs.getLong("overdue_tasks"),
                        rs.getDouble("completion_rate"),
                        rs.getLong("created_7d"),
                        rs.getLong("completed_7d"),
                        rs.getLong("active_projects"),
                        rs.getLong("active_users")
                ))
                .single();
    }

    public List<TrendDataPointDto> getTrends(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));

        return jdbcClient.sql("""
                with calendar as (
                    select generate_series(
                        date_trunc('day', now()) - (:days - 1) * interval '1 day',
                        date_trunc('day', now()),
                        interval '1 day'
                    )::date as day
                ),
                created as (
                    select date_trunc('day', created_at)::date as day, count(*) as count
                    from ms_tasks
                    where created_at >= date_trunc('day', now()) - (:days - 1) * interval '1 day'
                    group by 1
                ),
                completed as (
                    select date_trunc('day', coalesce(resolved_time, modified_at))::date as day, count(*) as count
                    from ms_tasks t
                    join ms_task_statuses s on s.id = t.status_id and s.is_terminal = true
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
                """)
                .param("days", safeDays)
                .query((rs, rowNum) -> new TrendDataPointDto(
                        rs.getString("date_str"),
                        rs.getLong("created_count"),
                        rs.getLong("completed_count")
                ))
                .list();
    }

    public List<ProjectDistributionDto> getProjectDistribution() {
        return jdbcClient.sql("""
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
                from ms_task_projects p
                left join ms_tasks t on t.project_id = p.id
                left join ms_task_statuses s on s.id = t.status_id
                where p.state = 'A'
                group by p.id, p.name
                order by total_tasks desc, p.name asc
                limit 15
                """)
                .query((rs, rowNum) -> new ProjectDistributionDto(
                        rs.getLong("project_id"),
                        rs.getString("project_name"),
                        rs.getLong("total_tasks"),
                        rs.getLong("active_tasks"),
                        rs.getLong("completed_tasks"),
                        rs.getDouble("progress_percent")
                ))
                .list();
    }

    public List<UserWorkloadDto> getUserWorkload() {
        return jdbcClient.sql("""
                select
                    u.id as user_id,
                    u.name as user_name,
                    u.login as user_login,
                    count(distinct t.id) as assigned_tasks,
                    count(distinct t.id) filter (where coalesce(s.is_terminal, false) = true) as completed_tasks
                from md_users u
                left join ms_task_members tm on tm.user_id = u.id
                left join ms_tasks t on t.id = tm.task_id
                left join ms_task_statuses s on s.id = t.status_id
                where u.state = 'A'
                group by u.id, u.name, u.login
                order by assigned_tasks desc, u.name asc
                limit 15
                """)
                .query((rs, rowNum) -> new UserWorkloadDto(
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getString("user_login"),
                        rs.getLong("assigned_tasks"),
                        rs.getLong("completed_tasks")
                ))
                .list();
    }
}
