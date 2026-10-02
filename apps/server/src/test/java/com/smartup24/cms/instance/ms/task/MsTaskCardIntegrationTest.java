package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.controller.MsTaskCommentController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskController;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskCommentRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import com.smartup24.cms.instance.ms.task.service.MsTaskAccess;
import com.smartup24.cms.instance.ms.task.service.MsTaskCommentService;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * The parts of a task the module keeps beside the general runtime (ADR-0032, 8): its participants, the "seen" mark and
 * its comments, over PostgreSQL with the transactions the annotations declare — a read-only read that wrote would fail.
 */
@Testcontainers
class MsTaskCardIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("task_card_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static JdbcClient jdbc;
    static DriverManagerDataSource dataSource;
    static MdScopeRepository scopeRepository;
    static MdRoleRepository roles;
    static MdScopeService scopes;
    static MockMvc mvc;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var audit =
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        scopeRepository = new MdScopeRepository(jdbc);
        roles = new MdRoleRepository(jdbc);
        scopes = new MdScopeService(
                scopeRepository,
                new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                audit);
        var access = new MsTaskAccess(new MsTaskRepository(jdbc), scopes);
        var events = mock(ApplicationEventPublisher.class);
        var members = transactional(
                new MsTaskMemberService(
                        new MsTaskMemberRepository(jdbc), new MsTaskStatusRepository(jdbc), access, events),
                transactions);
        var comments = transactional(
                new MsTaskCommentService(
                        new MsTaskCommentRepository(jdbc), access, members, mock(MfFileService.class), events, audit),
                transactions);
        mvc = MockMvcBuilders.standaloneSetup(new MsTaskController(members), new MsTaskCommentController(comments))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void readingTheParticipantsWritesNothingAndTheViewCommandMarksTheTaskViewed() throws Exception {
        Long actor = user("Card viewer");
        assignScope(actor, "ALL");
        Long author = user("Card author");
        Long task = task("Unseen", author);
        addMember(task, actor, MsTaskPref.INVOLVE_RESPONSIBLE);
        signIn(actor);
        String before = rowState(task);

        mvc.perform(get("/api/v1/tasks/{id}/members", task))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(actor));

        assertThat(rowState(task)).as("GET /tasks/{id}/members writes nothing").isEqualTo(before);
        assertThat(viewed(task, actor)).isFalse();

        mvc.perform(post("/api/v1/tasks/{id}/view", task)).andExpect(status().isNoContent());

        assertThat(viewed(task, actor)).isTrue();
        assertThat(rowState(task)).isNotEqualTo(before);
    }

    @Test
    void theParticipantsAndTheViewCommandOfAnInvisibleTaskAreNotFoundAndWriteNothing() throws Exception {
        Long owner = user("Hidden owner");
        Long outsider = user("Hidden outsider");
        assignScope(outsider, "SELF");
        Long task = task("Hidden card", owner);
        addMember(task, owner, MsTaskPref.INVOLVE_RESPONSIBLE);
        signIn(outsider);
        String before = rowState(task);

        mvc.perform(get("/api/v1/tasks/{id}/members", task)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/{id}/view", task)).andExpect(status().isNotFound());

        assertThat(rowState(task)).isEqualTo(before);
    }

    @Test
    void commentCreateAndListExposeOnlyPublicAuthorDisplayFields() throws Exception {
        Long actor = user("Public Author");
        assignScope(actor, "ALL");
        Long task = task("Commented", actor);
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
                .andExpect(jsonPath("$.items[0].userName").value("Public Author"))
                .andExpect(jsonPath("$.items[0].userLogin").value(login(actor)))
                .andExpect(jsonPath("$.items[0].userEmail").doesNotExist());
    }

    @Test
    void commentListKeepsDeletedAuthorIdentityNullable() throws Exception {
        Long actor = user("Comment viewer");
        assignScope(actor, "ALL");
        Long task = task("Orphan comment", actor);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.executeUpdate("""
                    insert into ms_task_comments (task_id, user_id, text_markdown)
                    values (""" + task + ", 9223372036854770000, 'Deleted author')");
            statement.execute("set session_replication_role = origin");
        }
        signIn(actor);

        mvc.perform(get("/api/v1/tasks/{taskId}/comments", task))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(9223372036854770000L))
                .andExpect(jsonPath("$.items[0].userName").value(nullValue()))
                .andExpect(jsonPath("$.items[0].userLogin").value(nullValue()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager transactions) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static String rowState(Long task) {
        return jdbc.sql("""
                select t.modified_at || '|' || t.revision
                       || '|' || coalesce((select string_agg(m.user_id || m.involve_kind || m.is_viewed, ','
                                                             order by m.user_id, m.involve_kind)
                                           from ms_task_members m where m.task_id = t.id), '')
                       || '|' || (select count(*) from audit_log a
                                  where a.table_name = 'ms_tasks' and a.row_pk = t.id::text)
                from ms_tasks t where t.id = :id
                """).param("id", task).query(String.class).single();
    }

    private static boolean viewed(Long task, Long user) {
        return jdbc.sql("select bool_and(is_viewed) from ms_task_members where task_id = :task and user_id = :user")
                .param("task", task)
                .param("user", user)
                .query(Boolean.class)
                .single();
    }

    private static Long user(String name) {
        String login = "task-card-" + SEQUENCE.incrementAndGet();
        return jdbc.sql("""
                insert into md_users (name, login, email, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change)
                values (:name, :login, :login || '@example.invalid', 'x', 'A', 'ru', 'UTC', '{}', false, false)
                returning id
                """)
                .param("name", name)
                .param("login", login)
                .query(Long.class)
                .single();
    }

    private static String login(Long userId) {
        return jdbc.sql("select login from md_users where id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private static Long task(String title, Long reporter) {
        return jdbc.sql("""
                insert into ms_tasks (title, priority, reporter_id, created_by, modified_by)
                values (:title, 'medium', :reporter, :reporter, :reporter)
                returning id
                """)
                .param("title", title)
                .param("reporter", reporter)
                .query(Long.class)
                .single();
    }

    private static void addMember(Long task, Long user, String kind) {
        jdbc.sql("""
                insert into ms_task_members (task_id, user_id, involve_kind, is_viewed)
                values (:task, :user, :kind, false)
                """)
                .param("task", task)
                .param("user", user)
                .param("kind", kind)
                .update();
    }

    private static void assignScope(Long userId, String rule) {
        var role = roles.create("Task card " + rule + " " + SEQUENCE.incrementAndGet(), null, "A", 100);
        scopeRepository.setRoleRule(role.id(), rule);
        roles.assignRolesToUser(userId, List.of(role.id()));
        scopeRepository.replaceUserOrgUnits(userId, List.of());
        scopes.recalculateFor(userId);
    }

    private static void signIn(Long userId) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId,
                login(userId),
                login(userId) + "@example.invalid",
                1000L,
                false,
                Set.of("*.*"),
                1L,
                false,
                0,
                null));
    }
}
