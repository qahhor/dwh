package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.task.controller.MsProjectController;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.service.MsProjectService;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Project write contracts backed by PostgreSQL and real transaction boundaries. */
@Testcontainers
class MsProjectWriteIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("project_write_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Map<String, Object> FIXTURE_ATTRIBUTES = Map.of("fixture", "kept", "rank", 1);

    static JdbcClient jdbc;
    static DriverManagerDataSource dataSource;
    static ObjectMapper objectMapper;
    static MsProjectRepository projects;
    static MsProjectService projectService;
    static SearchChangePublisher searchChangePublisher;
    static AuditLogService auditLogService;
    static DataSourceTransactionManager transactions;
    static TransactionTemplate transactionTemplate;
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
        objectMapper = new ObjectMapper();
        projects = new MsProjectRepository(jdbc, objectMapper);
        searchChangePublisher = mock(SearchChangePublisher.class);

        auditLogService =
                new AuditLogService(new AuditLogRepository(jdbc, objectMapper), null, new AuditDataRedactor());
        var serviceTarget =
                new MsProjectService(projects, acceptingCustomFields(), searchChangePublisher, auditLogService);
        transactions = new DataSourceTransactionManager(dataSource);
        projectService = transactional(serviceTarget, transactions, MsProjectService.class);
        transactionTemplate = new TransactionTemplate(transactions);

        mvc = MockMvcBuilders.standaloneSetup(new MsProjectController(projectService, null))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    @BeforeEach
    void clearIndexerCalls() {
        clearInvocations(searchChangePublisher);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "\b"})
    void rejectsBlankPatchNameBeforeAnyMutation(String invalidName) throws Exception {
        Long actor = user("Blank-name actor");
        Long projectId = project(actor, "Original name", "Original description", "A");
        var before = projects.findById(projectId).orElseThrow();
        long auditBefore = auditCount(projectId);
        signIn(actor, Set.of("*.*"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content(json(Map.of("name", invalidName, "description", "must not persist"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));

        assertUnchanged(projectId, before, auditBefore);
        verifyNoInteractions(searchChangePublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X", "a", "AP"})
    void rejectsInvalidPatchStateBeforeAnyMutation(String invalidState) throws Exception {
        Long actor = user("Invalid-state actor");
        Long projectId = project(actor, "Original state project", "Original description", "A");
        var before = projects.findById(projectId).orElseThrow();
        long auditBefore = auditCount(projectId);
        signIn(actor, Set.of("*.*"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content(json(Map.of("state", invalidState, "description", "must not persist"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("state"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid"));

        assertUnchanged(projectId, before, auditBefore);
        verifyNoInteractions(searchChangePublisher);
    }

    @Test
    void rejectsInvalidCreateStateBeforeAnyMutation() throws Exception {
        Long actor = user("Invalid-create actor");
        long projectsBefore = projectCount();
        long auditBefore = projectAuditCount();
        signIn(actor, Set.of("*.*"));

        mvc.perform(post("/api/v1/tasks/projects")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "name", unique("Invalid create"),
                                "description", "must not persist",
                                "state", "X"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("state"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid"));

        assertThat(projectCount()).isEqualTo(projectsBefore);
        assertThat(projectAuditCount()).isEqualTo(auditBefore);
        verifyNoInteractions(searchChangePublisher);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void serviceRejectsMissingOrBlankCreateNameBeforeAnyMutation(String invalidName) {
        long projectsBefore = projectCount();
        long auditBefore = projectAuditCount();

        Throwable failure = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            try {
                projectService.createProject(invalidName, "must not persist", null, null, null);
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        });

        assertThat(failure).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) failure;
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getMessageKey()).isEqualTo("error.project.name_required");
        assertThat(exception.getFieldErrors())
                .containsExactly(FieldErrorItem.keyed("name", "required", "error.project.name_required"));
        assertThat(projectCount()).isEqualTo(projectsBefore);
        assertThat(projectAuditCount()).isEqualTo(auditBefore);
        verifyNoInteractions(searchChangePublisher);
    }

    @Test
    void createTrimsNameDefaultsStateAndAuditsNormalizedValue() throws Exception {
        Long actor = user("Create actor");
        String normalizedName = unique("Normalized create");
        signIn(actor, Set.of("*.*"));

        String response = mvc.perform(post("/api/v1/tasks/projects")
                        .contentType("application/json")
                        .content(json(Map.of("name", "  " + normalizedName + "  ", "description", "Description"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(normalizedName))
                .andExpect(jsonPath("$.state").value("A"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long projectId = objectMapper.readTree(response).get("id").asLong();
        var created = projects.findById(projectId).orElseThrow();
        assertThat(created.name()).isEqualTo(normalizedName);
        assertThat(created.state()).isEqualTo("A");
        assertThat(auditNewValue(projectId, "name")).isEqualTo(normalizedName);
        assertThat(auditNewValue(projectId, "state")).isEqualTo("A");
        verify(searchChangePublisher).projectChanged(projectId);
    }

    @Test
    void patchTrimsNameAndAuditsNormalizedValue() throws Exception {
        Long actor = user("Patch actor");
        Long projectId = project(actor, "Before patch", "Description", "A");
        String normalizedName = unique("Normalized patch");
        signIn(actor, Set.of("*.*"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content(json(Map.of("name", "  " + normalizedName + "  "))))
                .andExpect(status().isNoContent());

        assertThat(projects.findById(projectId).orElseThrow().name()).isEqualTo(normalizedName);
        assertThat(auditNewValue(projectId, "name")).isEqualTo(normalizedName);
        verify(searchChangePublisher).projectChanged(projectId);
    }

    @Test
    void patchLeavesOmittedAndNullFieldsUnchangedButClearsExplicitEmptyDescription() throws Exception {
        Long actor = user("Sparse-patch actor");
        Long projectId = project(actor, "Sparse patch", "Original description", "P");
        var before = projects.findById(projectId).orElseThrow();
        assertThat(before.attributes()).isEqualTo(FIXTURE_ATTRIBUTES);
        signIn(actor, Set.of("*.*"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNoContent());
        assertThat(projects.findById(projectId).orElseThrow())
                .usingRecursiveComparison()
                .ignoringFields("revision")
                .isEqualTo(before);

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content("{\"name\":null,\"description\":null,\"state\":null}"))
                .andExpect(status().isNoContent());
        assertThat(projects.findById(projectId).orElseThrow())
                .usingRecursiveComparison()
                .ignoringFields("revision")
                .isEqualTo(before);

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content("{\"description\":\"\"}"))
                .andExpect(status().isNoContent());
        assertThat(projects.findById(projectId).orElseThrow())
                .isEqualTo(new MsProjectRepository.ProjectRecord(
                        before.id(),
                        before.name(),
                        "",
                        before.state(),
                        before.attributes(),
                        before.createdAt(),
                        before.createdBy(),
                        // Three saves, each from the revision before it (plan item 3.6).
                        before.revision() + 3));
    }

    @Test
    void overlappingSaveFromAStaleRevisionIsRefused() throws Exception {
        Long actor = user("Overlapping-update actor");
        Long projectId = project(actor, "Concurrent project", "Original description", "A", Map.of("owner", "original"));
        String originalName = projects.findById(projectId).orElseThrow().name();
        var readComplete = new CountDownLatch(1);
        var continueUpdate = new CountDownLatch(1);
        var pausingProjects = new PausingProjectRepository(jdbc, objectMapper, readComplete, continueUpdate);
        var localIndexer = mock(SearchChangePublisher.class);
        var serviceTarget =
                new MsProjectService(pausingProjects, acceptingCustomFields(), localIndexer, auditLogService);
        var overlappingService = transactional(serviceTarget, transactions, MsProjectService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var nameUpdate = executor.submit(
                    () -> overlappingService.updateProject(projectId, "  Concurrent rename  ", null, null, null, 1L));
            assertThat(readComplete.await(10, TimeUnit.SECONDS)).isTrue();

            projectService.updateProject(projectId, null, null, null, Map.of("owner", "concurrent"), 1L);
            continueUpdate.countDown();
            // The rename was made from revision 1, which the other save has just replaced (plan item 3.6): it is
            // refused whole instead of writing over what it never saw.
            assertThatThrownBy(() -> nameUpdate.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .hasMessageContaining("error.common.revision_conflict");
        } finally {
            continueUpdate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        var after = projects.findById(projectId).orElseThrow();
        assertThat(after.name()).isEqualTo(originalName);
        assertThat(after.description()).isEqualTo("Original description");
        assertThat(after.state()).isEqualTo("A");
        assertThat(after.attributes()).isEqualTo(Map.of("owner", "concurrent"));
        assertThat(after.revision()).isEqualTo(2L);
        assertThat(auditCount(projectId)).isEqualTo(1);
        verify(searchChangePublisher).projectChanged(projectId);
        verify(localIndexer, never()).projectChanged(projectId);
    }

    @Test
    void missingProjectWinsOverPayloadValidation() throws Exception {
        Long actor = user("Missing-project actor");
        long projectsBefore = projectCount();
        long auditBefore = projectAuditCount();
        signIn(actor, Set.of("*.*"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", Long.MAX_VALUE)
                        .header("If-Match", ifMatch("ms_task_projects", Long.MAX_VALUE))
                        .contentType("application/json")
                        .content("{\"name\":\"   \",\"state\":\"X\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("project_not_found"));

        assertThat(projectCount()).isEqualTo(projectsBefore);
        assertThat(projectAuditCount()).isEqualTo(auditBefore);
        verifyNoInteractions(searchChangePublisher);
    }

    @Test
    void permissionRejectionPreventsProjectMutation() throws Exception {
        Long owner = user("Project owner");
        Long actor = user("Unauthorized actor");
        Long projectId = project(owner, "Protected project", "Original description", "A");
        var before = projects.findById(projectId).orElseThrow();
        long auditBefore = auditCount(projectId);
        signIn(actor, Set.of(MsTaskPref.FORM_PROJECTS + ".view"));

        mvc.perform(patch("/api/v1/tasks/projects/{id}", projectId)
                        .header("If-Match", ifMatch("ms_task_projects", projectId))
                        .contentType("application/json")
                        .content("{\"name\":\"Forbidden rename\",\"description\":\"must not persist\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        assertUnchanged(projectId, before, auditBefore);
        verifyNoInteractions(searchChangePublisher);
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

    private static Long user(String name) {
        String login = "project-write-" + SEQUENCE.incrementAndGet();
        return jdbc.sql("""
                insert into md_users (name, login, email, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change)
                values (:name, :login, :login || '@example.invalid', 'x', 'A', 'ru', 'UTC',
                        '{}', false, false)
                returning id
                """)
                .param("name", name)
                .param("login", login)
                .query(Long.class)
                .single();
    }

    private static Long project(Long actor, String name, String description, String state) {
        return project(actor, name, description, state, FIXTURE_ATTRIBUTES);
    }

    private static Long project(
            Long actor, String name, String description, String state, Map<String, Object> attributes) {
        return jdbc.sql("""
                insert into ms_task_projects (name, description, state, attributes, created_by)
                values (:name, :description, :state, cast(:attributes as jsonb), :actor)
                returning id
                """)
                .param("name", unique(name))
                .param("description", description)
                .param("state", state)
                .param("attributes", toJson(attributes))
                .param("actor", actor)
                .query(Long.class)
                .single();
    }

    private static void signIn(Long userId, Set<String> permissions) {
        String login = jdbc.sql("select login from md_users where id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId, login, login + "@example.invalid", 1000L, false, permissions, 1L, false, 0, null));
    }

    private static String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String toJson(Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Test attributes must be JSON serializable", exception);
        }
    }

    private static String unique(String prefix) {
        return prefix + " " + SEQUENCE.incrementAndGet();
    }

    private static void assertUnchanged(Long projectId, MsProjectRepository.ProjectRecord before, long auditBefore) {
        assertThat(projects.findById(projectId).orElseThrow())
                .usingRecursiveComparison()
                .ignoringFields("revision")
                .isEqualTo(before);
        assertThat(auditCount(projectId)).isEqualTo(auditBefore);
    }

    private static long projectCount() {
        return jdbc.sql("select count(*) from ms_task_projects")
                .query(Long.class)
                .single();
    }

    private static long projectAuditCount() {
        return jdbc.sql("select count(*) from audit_log where table_name = 'ms_task_projects'")
                .query(Long.class)
                .single();
    }

    private static long auditCount(Long projectId) {
        return jdbc.sql("""
                select count(*) from audit_log
                where table_name = 'ms_task_projects' and row_pk = :rowPk
                """)
                .param("rowPk", String.valueOf(projectId))
                .query(Long.class)
                .single();
    }

    private static String auditNewValue(Long projectId, String field) {
        return jdbc.sql("""
                select new_row ->> :field
                from audit_log
                where table_name = 'ms_task_projects' and row_pk = :rowPk
                order by id desc
                limit 1
                """)
                .param("field", field)
                .param("rowPk", String.valueOf(projectId))
                .query(String.class)
                .single();
    }

    private static final class PausingProjectRepository extends MsProjectRepository {

        private final CountDownLatch readComplete;
        private final CountDownLatch continueUpdate;

        private PausingProjectRepository(
                JdbcClient jdbcClient,
                ObjectMapper mapper,
                CountDownLatch readComplete,
                CountDownLatch continueUpdate) {
            super(jdbcClient, mapper);
            this.readComplete = readComplete;
            this.continueUpdate = continueUpdate;
        }

        @Override
        public Optional<ProjectRecord> findById(Long id) {
            Optional<ProjectRecord> project = super.findById(id);
            readComplete.countDown();
            try {
                if (!continueUpdate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the overlapping update");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the overlapping update", exception);
            }
            return project;
        }
    }

    /** {@code If-Match} with the current revision of the row, as a client that just read it sends (item 3.6). */
    private static String ifMatch(String table, Object id) {
        Long revision = jdbc.sql("select revision from " + table + " where id = :id")
                .param("id", ((Number) id).longValue())
                .query(Long.class)
                .optional()
                .orElse(1L);
        return "\"" + revision + "\"";
    }

    /** Custom fields that accept any attributes and store them as given. */
    private static MdCustomFieldService acceptingCustomFields() {
        MdCustomFieldService fields = mock(MdCustomFieldService.class);
        when(fields.checkedAttributes(anyString(), any())).thenAnswer(call -> call.getArgument(1));
        return fields;
    }
}
