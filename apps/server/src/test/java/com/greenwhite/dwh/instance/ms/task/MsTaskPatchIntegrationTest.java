package com.greenwhite.dwh.instance.ms.task;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.config.error.GlobalExceptionHandler;
import com.greenwhite.dwh.instance.kauth.security.RequiresPermissionInterceptor;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.ms.task.controller.MsTaskCommentController;
import com.greenwhite.dwh.instance.ms.task.controller.MsTaskController;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsProjectRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskCommentRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskMemberRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskStatusRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskTypeRepository;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskCommentService;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contracts backed by PostgreSQL, including transaction boundaries. */
@Testcontainers
class MsTaskPatchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("task_patch_test").withUsername("test_user").withPassword("test_pass");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static JdbcClient jdbc;
    static DriverManagerDataSource dataSource;
    static MdScopeRepository scopeRepository;
    static MdRoleRepository roles;
    static MdScopeService scopes;
    static MockMvc mvc;
    static Long rootUnit;
    static Long regionUnit;
    static Long childUnit;
    static Long siblingUnit;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var objectMapper = new ObjectMapper();

        var audit = new AuditLogService(
                new AuditLogRepository(jdbc, objectMapper), null, new AuditDataRedactor());
        scopeRepository = new MdScopeRepository(jdbc);
        roles = new MdRoleRepository(jdbc);
        scopes = new MdScopeService(
                scopeRepository,
                new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                audit);

        var taskServiceTarget = new MsTaskService(
                new MsTaskRepository(jdbc, objectMapper),
                new MsTaskStatusRepository(jdbc),
                new MsTaskTypeRepository(jdbc),
                new MsTaskMemberRepository(jdbc),
                new MsProjectRepository(jdbc, objectMapper),
                mock(MdCustomFieldService.class),
                scopes,
                mock(MfFileService.class),
                mock(ApplicationEventPublisher.class),
                mock(com.greenwhite.dwh.instance.search.SearchChangePublisher.class),
                audit);
        MsTaskService taskService = transactional(taskServiceTarget, transactions, MsTaskService.class);

        var commentServiceTarget = new MsTaskCommentService(
                new MsTaskCommentRepository(jdbc),
                taskService,
                mock(MfFileService.class),
                mock(ApplicationEventPublisher.class),
                audit);
        MsTaskCommentService commentService = transactional(
                commentServiceTarget, transactions, MsTaskCommentService.class);

        mvc = MockMvcBuilders.standaloneSetup(
                        new MsTaskController(taskService),
                        new MsTaskCommentController(commentService))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        rootUnit = orgUnit(null, "root");
        regionUnit = orgUnit(rootUnit, "region");
        childUnit = orgUnit(regionUnit, "child");
        siblingUnit = orgUnit(rootUnit, "sibling");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void patchLeavesOmittedFieldsUntouchedAndClearsExplicitNullableFields() throws Exception {
        Long actor = user("Actor", null);
        assignScope(actor, "ALL", List.of());
        Long responsible = user("Responsible", null);
        Long project = project(actor);
        Long parent = task("Parent", actor, actor, null, null);
        Long task = task("Original", actor, actor, project, parent);
        addMember(task, responsible, MsTaskPref.INVOLVE_RESPONSIBLE);
        signIn(actor);

        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isNoContent());

        Map<String, Object> afterRename = taskValues(task);
        assertThat(afterRename.get("project_id")).isEqualTo(project);
        assertThat(afterRename.get("parent_task_id")).isEqualTo(parent);
        assertThat(afterRename.get("begin_time")).isNotNull();
        assertThat(afterRename.get("end_time")).isNotNull();
        assertThat(responsible(task)).isEqualTo(responsible);

        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("""
                                {"projectId":null,"parentTaskId":null,"responsibleUserId":null,
                                 "beginTime":null,"endTime":null}
                                """))
                .andExpect(status().isNoContent());

        Map<String, Object> afterClear = taskValues(task);
        assertThat(afterClear.get("project_id")).isNull();
        assertThat(afterClear.get("parent_task_id")).isNull();
        assertThat(afterClear.get("begin_time")).isNull();
        assertThat(afterClear.get("end_time")).isNull();
        assertThat(responsible(task)).isNull();
        assertThat(auditCount(task)).isEqualTo(2);
    }

    @Test
    void omittedObserverListPreservesObserversAndEmptyListClearsThem() throws Exception {
        Long actor = user("Observer actor", null);
        assignScope(actor, "ALL", List.of());
        Long observer = user("Observer", null);
        Long task = task("Observed", actor, actor, null, null);
        addMember(task, observer, MsTaskPref.INVOLVE_OBSERVER);
        signIn(actor);

        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"title\":\"Still observed\"}"))
                .andExpect(status().isNoContent());
        assertThat(members(task, MsTaskPref.INVOLVE_OBSERVER)).containsExactly(observer);

        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"observerUserIds\":[]}"))
                .andExpect(status().isNoContent());
        assertThat(members(task, MsTaskPref.INVOLVE_OBSERVER)).isEmpty();
    }

    @Test
    void invalidParticipantRollsBackTaskMembersAndAuditTogether() throws Exception {
        Long actor = user("Rollback actor", null);
        assignScope(actor, "ALL", List.of());
        Long responsible = user("Old responsible", null);
        Long task = task("Original title", actor, actor, null, null);
        addMember(task, responsible, MsTaskPref.INVOLVE_RESPONSIBLE);
        signIn(actor);

        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"title\":\"Must roll back\",\"responsibleUserId\":9223372036854770000}"))
                .andExpect(status().isNotFound());

        assertThat(taskValues(task).get("title")).isEqualTo("Original title");
        assertThat(responsible(task)).isEqualTo(responsible);
        assertThat(auditCount(task)).isZero();
    }

    @Test
    void taskPatchPreservesAllSubtreeUnitsSelfAndNotFoundScopeSemantics() throws Exception {
        Long allActor = user("All actor", null);
        assignScope(allActor, "ALL", List.of());
        Long allTarget = task("ALL visible", user("All outsider", siblingUnit), user("All creator", siblingUnit), null, null);
        assertPatchVisible(allActor, allTarget);

        Long selfActor = user("Self actor", null);
        assignScope(selfActor, "SELF", List.of());
        assertPatchVisible(selfActor, task("SELF visible", selfActor, selfActor, null, null));
        assertPatchHidden(selfActor, task("SELF hidden", allActor, allActor, null, null));

        Long unitsActor = user("Units actor", regionUnit);
        assignScope(unitsActor, "UNITS", List.of(regionUnit));
        Long regionParticipant = user("Region participant", regionUnit);
        Long childParticipant = user("Child participant", childUnit);
        assertPatchVisible(unitsActor, task("UNITS visible", regionParticipant, regionParticipant, null, null));
        assertPatchHidden(unitsActor, task("UNITS hidden", childParticipant, childParticipant, null, null));

        Long subtreeActor = user("Subtree actor", regionUnit);
        assignScope(subtreeActor, "SUBTREE", List.of(regionUnit));
        Long siblingParticipant = user("Sibling participant", siblingUnit);
        assertPatchVisible(subtreeActor, task("SUBTREE visible", childParticipant, childParticipant, null, null));
        assertPatchHidden(subtreeActor, task("SUBTREE hidden", siblingParticipant, siblingParticipant, null, null));
    }

    @Test
    void commentCreateAndListExposeOnlyPublicAuthorDisplayFields() throws Exception {
        Long actor = user("Public Author", null);
        assignScope(actor, "ALL", List.of());
        Long task = task("Commented", actor, actor, null, null);
        signIn(actor);

        mvc.perform(post("/api/v1/tasks/{taskId}/comments", task)
                        .contentType("application/json")
                        .content("{\"textMarkdown\":\"Hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userName").value("Public Author"))
                .andExpect(jsonPath("$.userLogin").value(login(actor)))
                .andExpect(jsonPath("$.userEmail").doesNotExist());

        mvc.perform(get("/api/v1/tasks/{taskId}/comments", task))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userName").value("Public Author"))
                .andExpect(jsonPath("$[0].userLogin").value(login(actor)))
                .andExpect(jsonPath("$[0].userEmail").doesNotExist());
    }

    @Test
    void commentListKeepsDeletedAuthorIdentityNullable() throws Exception {
        Long actor = user("Comment viewer", null);
        assignScope(actor, "ALL", List.of());
        Long task = task("Orphan comment", actor, actor, null, null);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.executeUpdate("""
                    insert into ms_task_comments (task_id, user_id, text_markdown)
                    values (""" + task + ", 9223372036854770000, 'Deleted author')");
            statement.execute("set session_replication_role = origin");
        }
        signIn(actor);

        mvc.perform(get("/api/v1/tasks/{taskId}/comments", task))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(9223372036854770000L))
                .andExpect(jsonPath("$[0].userName").value(nullValue()))
                .andExpect(jsonPath("$[0].userLogin").value(nullValue()));
    }

    private static void assertPatchVisible(Long actor, Long task) throws Exception {
        signIn(actor);
        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"title\":\"Visible " + task + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(taskValues(task).get("title")).isEqualTo("Visible " + task);
    }

    private static void assertPatchHidden(Long actor, Long task) throws Exception {
        String before = (String) taskValues(task).get("title");
        signIn(actor);
        mvc.perform(patch("/api/v1/tasks/{id}", task)
                        .contentType("application/json")
                        .content("{\"title\":\"Forbidden\"}"))
                .andExpect(status().isNotFound());
        assertThat(taskValues(task).get("title")).isEqualTo(before);
    }

    private static <T> T transactional(T target, DataSourceTransactionManager transactions, Class<T> type) {
        var factory = new TransactionProxyFactoryBean();
        factory.setTarget(target);
        factory.setProxyTargetClass(true);
        factory.setTransactionManager(transactions);
        var attributes = new Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        factory.setTransactionAttributes(attributes);
        factory.afterPropertiesSet();
        return type.cast(factory.getObject());
    }

    private static Long user(String name, Long orgUnitId) {
        String login = "task-patch-" + SEQUENCE.incrementAndGet();
        return jdbc.sql("""
                insert into md_users (name, login, email, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change, org_unit_id)
                values (:name, :login, :login || '@example.invalid', 'x', 'A', 'ru', 'UTC',
                        '{}', false, false, :orgUnitId)
                returning id
                """)
                .param("name", name)
                .param("login", login)
                .param("orgUnitId", orgUnitId)
                .query(Long.class)
                .single();
    }

    private static String login(Long userId) {
        return jdbc.sql("select login from md_users where id = :id")
                .param("id", userId).query(String.class).single();
    }

    private static Long project(Long actor) {
        return jdbc.sql("""
                insert into ms_task_projects (name, description, state, attributes, created_by)
                values (:name, '', 'A', '{}', :actor) returning id
                """)
                .param("name", "Project " + SEQUENCE.incrementAndGet())
                .param("actor", actor)
                .query(Long.class)
                .single();
    }

    private static Long task(String title, Long reporter, Long creator, Long project, Long parent) {
        return jdbc.sql("""
                insert into ms_tasks (project_id, parent_task_id, title, description_markdown,
                                      status_id, priority, reporter_id, attributes, begin_time, end_time,
                                      created_by, modified_by)
                values (:project, :parent, :title, '',
                        (select id from ms_task_statuses order by id limit 1), 'medium', :reporter, '{}',
                        '2026-09-05T08:00:00Z', '2026-09-05T09:00:00Z', :creator, :creator)
                returning id
                """)
                .param("project", project)
                .param("parent", parent)
                .param("title", title)
                .param("reporter", reporter)
                .param("creator", creator)
                .query(Long.class)
                .single();
    }

    private static void addMember(Long task, Long user, String kind) {
        jdbc.sql("""
                insert into ms_task_members (task_id, user_id, involve_kind, is_viewed)
                values (:task, :user, :kind, false)
                """).param("task", task).param("user", user).param("kind", kind).update();
    }

    private static Long responsible(Long task) {
        return jdbc.sql("""
                select user_id from ms_task_members
                where task_id = :task and involve_kind = 'R'
                """).param("task", task).query(Long.class).optional().orElse(null);
    }

    private static List<Long> members(Long task, String kind) {
        return jdbc.sql("""
                select user_id from ms_task_members
                where task_id = :task and involve_kind = :kind order by user_id
                """).param("task", task).param("kind", kind).query(Long.class).list();
    }

    private static Map<String, Object> taskValues(Long task) {
        return jdbc.sql("""
                select title, project_id, parent_task_id, begin_time, end_time
                from ms_tasks where id = :id
                """).param("id", task).query((rs, rowNum) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("title", rs.getString("title"));
            values.put("project_id", nullableLong(rs, "project_id"));
            values.put("parent_task_id", nullableLong(rs, "parent_task_id"));
            values.put("begin_time", rs.getTimestamp("begin_time") != null
                    ? rs.getTimestamp("begin_time").toInstant() : null);
            values.put("end_time", rs.getTimestamp("end_time") != null
                    ? rs.getTimestamp("end_time").toInstant() : null);
            return values;
        }).single();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column) != null ? rs.getLong(column) : null;
    }

    private static long auditCount(Long task) {
        return jdbc.sql("""
                select count(*) from audit_log
                where table_name = 'ms_tasks' and row_pk = :rowPk
                """).param("rowPk", String.valueOf(task)).query(Long.class).single();
    }

    private static Long orgUnit(Long parent, String code) {
        return jdbc.sql("""
                insert into md_org_units (parent_id, code, name, kind, state, order_no)
                values (:parent, :code, :name, 'department', 'A', 1) returning id
                """).param("parent", parent)
                .param("code", code + "-" + SEQUENCE.incrementAndGet())
                .param("name", code)
                .query(Long.class).single();
    }

    private static void assignScope(Long userId, String rule, List<Long> orgUnitIds) {
        var role = roles.create("Task patch " + rule + " " + SEQUENCE.incrementAndGet(), null, "A", 100);
        scopeRepository.setRoleRule(role.id(), rule);
        roles.assignRolesToUser(userId, List.of(role.id()));
        scopeRepository.replaceUserOrgUnits(userId, orgUnitIds);
        scopes.recalculateFor(userId);
    }

    private static void signIn(Long userId) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId, login(userId), login(userId) + "@example.invalid", 1000L,
                false, Set.of("*.*"), 1L, false, 0, null));
    }
}
