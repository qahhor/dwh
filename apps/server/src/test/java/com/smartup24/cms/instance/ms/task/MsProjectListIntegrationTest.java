package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import com.smartup24.cms.instance.ms.task.service.MsProjectListService;
import com.smartup24.cms.instance.ms.task.service.MsProjectListService.ProjectListItem;
import com.smartup24.cms.instance.ms.task.service.MsProjectQuery;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** The project list on the field registry (roadmap item 51), with task counts over the viewer's scope. */
class MsProjectListIntegrationTest {

    static JdbcClient jdbc;
    static MsProjectListService projects;
    static MdScopeService scopes;
    static MdRoleRepository roles;
    static Long admin;
    static Long outsider;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("project_list_test");
        jdbc = JdbcClient.create(ds);
        var audit =
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        roles = new MdRoleRepository(jdbc);
        scopes = new MdScopeService(
                new MdScopeRepository(jdbc),
                new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                audit);
        var config = new MsProjectQuery();
        var registry = new QueryListRegistry(List.of(MsProjectQuery.LIST), List.of(config.progressFields(scopes)));
        projects = new MsProjectListService(
                new QueryListRepository(jdbc),
                registry,
                new MsProjectRepository(jdbc, new ObjectMapper()),
                new MsTaskStatsRepository(jdbc),
                scopes);

        admin = user("pl_admin");
        roles.assignRolesToUser(
                admin, List.of(roles.findByPcode("admin").orElseThrow().id()));
        scopes.recalculateFor(admin);
        outsider = user("pl_outsider");

        Long open = status(false);
        Long closed = status(true);
        long alpha = project("pl Alpha", "A", "first");
        long beta = project("pl Beta", "A", "needle inside");
        project("pl Gamma", "P", null);
        task(alpha, closed, admin);
        task(alpha, open, admin);
        task(beta, closed, admin);
        task(beta, closed, admin);
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("By name by default, search in name and description, the old state filter kept, the real total")
    void byNameSearchAndState() {
        signIn(admin, "tasks.projects.view", "tasks.items.view");
        var page = projects.page(admin, null, null, null, null, "pl ", null);
        assertThat(names(page.items())).containsExactly("pl Alpha", "pl Beta", "pl Gamma");
        assertThat(page.totalEstimated()).isEqualTo(3);
        assertThat(names(projects.page(admin, null, null, null, null, "needle", null)
                        .items()))
                .containsExactly("pl Beta");
        assertThat(names(
                        projects.page(admin, null, null, null, null, "pl ", "P").items()))
                .containsExactly("pl Gamma");
    }

    @Test
    @DisplayName("3.5: a project picker searches by name and pages by a cursor, archived projects included")
    void pickerSearchesAndPages() {
        signIn(admin, "tasks.projects.view", "tasks.items.view");
        assertThat(names(projects.page(admin, 20, null, null, null, "alpha", null)
                        .items()))
                .containsExactly("pl Alpha");
        var first = projects.page(admin, 2, null, null, null, "pl ", null);
        assertThat(names(first.items())).containsExactly("pl Alpha", "pl Beta");
        assertThat(first.hasMore()).isTrue();
        var second = projects.page(admin, 2, first.nextCursor(), null, null, "pl ", null);
        assertThat(names(second.items())).containsExactly("pl Gamma");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    @DisplayName("Progress sorts the whole list on the server; counts are over the viewer's tasks")
    void progressSortsOnTheServer() {
        signIn(admin, "tasks.projects.view", "tasks.items.view");
        var page = projects.page(admin, null, null, null, "-progress", "pl ", null);
        assertThat(names(page.items())).containsExactly("pl Beta", "pl Alpha", "pl Gamma");
        assertThat(page.items()).extracting(ProjectListItem::progress).containsExactly(100, 50, 0);
        assertThat(page.items()).extracting(ProjectListItem::totalTasks).containsExactly(2, 2, 0);
        assertThat(names(projects.page(
                                admin,
                                null,
                                null,
                                "[{\"field\":\"progress\",\"op\":\"gte\",\"value\":50}]",
                                null,
                                "pl ",
                                null)
                        .items()))
                .containsExactly("pl Alpha", "pl Beta");
    }

    @Test
    @DisplayName("Without the right to view tasks there are no counts, no progress filter and no progress sort")
    void countsNeedTheTaskRight() {
        signIn(outsider, "tasks.projects.view");
        var page = projects.page(outsider, null, null, null, null, "pl ", null);
        assertThat(page.items()).extracting(ProjectListItem::progress).containsOnlyNulls();
        assertThatThrownBy(() -> projects.page(outsider, null, null, null, "progress", "pl ", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::code)
                                .containsExactly(QueryCompiler.SORT_INVALID));
    }

    @Test
    @DisplayName("ADR-0013: a scoped viewer lists only the projects in scope and counts only the tasks in scope")
    void listAndCountsFollowTheScope() {
        Long self = user("pl_self");
        var role = roles.create("pl SELF", null, "A", 100);
        new MdScopeRepository(jdbc).setRoleRule(role.id(), MdScopeService.RULE_SELF);
        roles.assignRolesToUser(self, List.of(role.id()));
        scopes.recalculateFor(self);
        signIn(self, "tasks.projects.view", "tasks.items.view");
        assertThat(projects.page(self, null, null, null, null, "pl ", null).items())
                .as("a project the viewer neither created, joined nor works in is not listed")
                .isEmpty();

        jdbc.sql("""
                        insert into ms_task_project_members (project_id, user_id, access_kind)
                        select id, :user, 'R' from ms_task_projects where name = 'pl Alpha'
                        """).param("user", self).update();
        var page = projects.page(self, null, null, null, null, "pl ", null);
        assertThat(names(page.items())).containsExactly("pl Alpha");
        assertThat(page.items())
                .as("a member sees the project but counts only the tasks they take part in")
                .extracting(ProjectListItem::totalTasks)
                .containsExactly(0);
    }

    private static List<String> names(List<ProjectListItem> items) {
        return items.stream().map(ProjectListItem::name).toList();
    }

    private static void signIn(Long userId, String... permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId, "viewer", "viewer@example.test", 1L, false, Set.of(permissions), 1L, false, 1L, null));
    }

    private static Long user(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC', '{}'::jsonb, false, false)
                        returning id
                        """).param("login", login).query(Long.class).single();
    }

    private static Long status(boolean terminal) {
        return jdbc.sql("select id from ms_task_statuses where is_terminal = :t order by id limit 1")
                .param("t", terminal)
                .query(Long.class)
                .single();
    }

    private static long project(String name, String state, String description) {
        return jdbc.sql("insert into ms_task_projects (name, state, description) values (:n, :s, :d) returning id")
                .param("n", name)
                .param("s", state)
                .param("d", description)
                .query(Long.class)
                .single();
    }

    private static void task(long projectId, Long statusId, Long reporter) {
        jdbc.sql("""
                        insert into ms_tasks (project_id, title, description_markdown, status_id, priority, reporter_id,
                                              attributes, created_by, modified_by)
                        values (:p, 'pl task', '', :s, 'medium', :r, '{}'::jsonb, :r, :r)
                        """)
                .param("p", projectId)
                .param("s", statusId)
                .param("r", reporter)
                .update();
    }
}
