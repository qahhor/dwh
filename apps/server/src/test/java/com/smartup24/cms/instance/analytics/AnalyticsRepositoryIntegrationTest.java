package com.smartup24.cms.instance.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.analytics.repository.AnalyticsRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR-0026: the dashboard reads tasks, projects and users through their published views. */
class AnalyticsRepositoryIntegrationTest {

    private static JdbcClient jdbc;
    private static AnalyticsRepository analytics;
    private static long user;
    private static long project;

    @BeforeAll
    static void setup() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("analytics_views"));
        analytics = new AnalyticsRepository(jdbc);
        user = id(
                "insert into md_users (name, login, email) values ('Analyst', 'analyst', 'a@example.test') returning id");
        project = id("insert into ms_task_projects (name) values ('Dashboard') returning id");
        long open = id("insert into ms_task_statuses (name, color) values ('Open', '#000') returning id");
        long done = id(
                "insert into ms_task_statuses (name, color, is_terminal) values ('Closed', '#fff', true) returning id");
        long first = task("First", open);
        task("Second", done);
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind) values (:task, :user, 'R')")
                .param("task", first)
                .param("user", user)
                .update();
    }

    @Test
    @DisplayName("ADR-0026: summary, trends, projects and workload come from the published views")
    void dashboardQueriesReadThePublishedViews() {
        var summary = analytics.getSummary();
        assertThat(summary.totalTasks()).isEqualTo(2);
        assertThat(summary.completedTasks()).isEqualTo(1);
        assertThat(summary.activeProjectsCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.activeUsersCount()).isGreaterThanOrEqualTo(1);

        assertThat(analytics.getTrends(7))
                .hasSize(7)
                .last()
                .satisfies(day -> assertThat(day.createdCount()).isEqualTo(2));
        assertThat(analytics.getProjectDistribution()).anySatisfy(row -> {
            assertThat(row.projectId()).isEqualTo(project);
            assertThat(row.totalTasks()).isEqualTo(2);
            assertThat(row.progressPercent()).isEqualTo(50.0);
        });
        assertThat(analytics.getUserWorkload()).anySatisfy(row -> {
            assertThat(row.userId()).isEqualTo(user);
            assertThat(row.userLogin()).isEqualTo("analyst");
            assertThat(row.assignedTasks()).isEqualTo(1);
        });
    }

    private static long task(String title, long status) {
        return jdbc.sql("""
                        insert into ms_tasks (project_id, title, status_id, reporter_id)
                        values (:project, :title, :status, :user) returning id
                        """)
                .param("project", project)
                .param("title", title)
                .param("status", status)
                .param("user", user)
                .query(Long.class)
                .single();
    }

    private static long id(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
