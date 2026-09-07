package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.security.ScopeFilter;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.ms.task.repository.*;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import com.greenwhite.dwh.instance.ms.task.service.MsProjectService;
import com.greenwhite.dwh.instance.search.typesense.*;
import com.greenwhite.dwh.instance.search.repository.SearchProjectionReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
class SearchRevisionIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("search_revisions").withUsername("test_user").withPassword("test_pass");
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static MsTaskService tasks;
    static MsProjectService projects;
    static SearchChangePublisher publisher;
    static long reporter;
    static DriverManagerDataSource database;

    @BeforeAll static void setup() {
        database = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure()).dataSource(database).load().migrate();
        jdbc = JdbcClient.create(database);
        var manager = new DataSourceTransactionManager(database);
        tx = new TransactionTemplate(manager);
        var mapper = new ObjectMapper();
        var scopes = mock(MdScopeService.class);
        when(scopes.filterForTasks(any())).thenReturn(ScopeFilter.unrestricted());
        publisher = proxied(new SearchChangePublisher(jdbc), manager);
        tasks = proxied(new MsTaskService(new MsTaskRepository(jdbc, mapper), new MsTaskStatusRepository(jdbc),
                new MsTaskTypeRepository(jdbc), new MsTaskMemberRepository(jdbc), new MsProjectRepository(jdbc, mapper),
                mock(MdCustomFieldService.class), scopes, mock(MfFileService.class), mock(ApplicationEventPublisher.class),
                publisher, mock(AuditLogService.class)), manager);
        projects = proxied(new MsProjectService(new MsProjectRepository(jdbc, mapper),
                mock(MdCustomFieldService.class), publisher, mock(AuditLogService.class)), manager);
        reporter = jdbc.sql("""
                insert into md_users(name,login,email,password_hash,state,language,timezone)
                values ('Reporter','revision-reporter','revision@example.invalid','x','A','ru','UTC') returning id
                """).query(Long.class).single();
    }

    @Test void taskAndRevisionBecomeVisibleTogetherOnlyAfterCommit() throws Exception {
        var inserted = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var id = new java.util.concurrent.atomic.AtomicLong();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var held = executor.submit(() -> tx.executeWithoutResult(status -> {
                id.set(create("committed task"));
                inserted.countDown();
                await(finish);
            }));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(count("ms_tasks", "id", id.get())).isZero();
                assertThat(count("search_projection_versions", "entity_id", id.get())).isZero();
            } finally { finish.countDown(); }
            held.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("ms_tasks", "id", id.get())).isOne();
        assertThat(count("search_projection_versions", "entity_id", id.get())).isOne();
    }

    @Test void rollbackRemovesBusinessRowAndRevision() {
        long id = tx.execute(status -> {
            long created = create("rolled back task");
            assertThat(count("search_projection_versions", "entity_id", created)).isOne();
            status.setRollbackOnly();
            return created;
        });
        assertThat(count("ms_tasks", "id", id)).isZero();
        assertThat(count("search_projection_versions", "entity_id", id)).isZero();
    }

    @Test void publisherRejectsCallsOutsideBusinessTransaction() {
        assertThatThrownBy(() -> publisher.changed("TASK", 99)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> publisher.lockStatusMembership(99)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void projectRenamePublishesProjectAndChildTextAtomicallyAndRollbackRestoresBoth() throws Exception {
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper());
        long project = projects.createProject("Before " + System.nanoTime(), "description", "A", null, reporter).id();
        long task = tasks.createTask(project, null, "Child", "body", "medium", null, null, null, null, null, reporter).id();
        assertThat(reader.read("PROJECT", project)).isPresent();
        var beforeProject = reader.read("PROJECT", project).orElseThrow();
        var beforeTask = reader.read("TASK", task).orElseThrow();
        tx.executeWithoutResult(transaction -> {
            projects.updateProject(project, "Uncommitted " + project, null, null, null);
            assertThat(reader.read("TASK", task).orElseThrow().document()).containsEntry("project_name", "Uncommitted " + project);
            assertThat(reader.read("PROJECT", project).orElseThrow().revision()).isEqualTo(2);
            transaction.setRollbackOnly();
        });
        assertThat(reader.read("PROJECT", project).orElseThrow()).isEqualTo(beforeProject);
        assertThat(reader.read("TASK", task).orElseThrow()).isEqualTo(beforeTask);
        projects.updateProject(project, "Committed " + project, null, null, null);
        assertThat(reader.read("TASK", task).orElseThrow().document()).containsEntry("project_name", "Committed " + project);
        assertThat(reader.read("TASK", task).orElseThrow().revision()).isEqualTo(2);
        assertThat(reader.read("PROJECT", project).orElseThrow().document()).containsEntry("name", "Committed " + project);
    }

    @Test void fingerprintIgnoresRevisionAndExcludedOrMissingSourceHasStableTombstone() {
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper());
        long project = projects.createProject("Fingerprint " + System.nanoTime(), "body", "A", null, reporter).id();
        assertThat(reader.read("PROJECT", project)).isPresent();
        var first = reader.read("PROJECT", project).orElseThrow();
        tx.executeWithoutResult(status -> publisher.changed("PROJECT", project));
        var next = reader.read("PROJECT", project).orElseThrow();
        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.fingerprint()).matches("[0-9a-f]{64}").isEqualTo(first.fingerprint());
        assertThat(next.document()).containsEntry("_projection_revision", 2L)
                .containsEntry("_projection_fingerprint", next.fingerprint());
        projects.updateProject(project, null, null, "P", null);
        var excluded = reader.read("PROJECT", project).orElseThrow();
        assertThat(excluded.document()).isNull();
        tx.executeWithoutResult(status -> {
            jdbc.sql("delete from ms_task_projects where id=:id").param("id", project).update();
            publisher.changed("PROJECT", project);
        });
        var missing = reader.read("PROJECT", project).orElseThrow();
        assertThat(missing.document()).isNull();
        assertThat(missing.fingerprint()).isEqualTo(excluded.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(reader.read("TASK", Long.MAX_VALUE)).isEmpty();
    }

    @Test void readerKeepsBodyAndRevisionFromTheSameStatementWhenCommitOccursDuringResultConsumption() throws Exception {
        long id = create("Snapshot one");
        var selected = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var observed = new org.springframework.jdbc.datasource.AbstractDataSource() {
            final java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException { return wrap(database.getConnection()); }
            @Override public java.sql.Connection getConnection(String name, String password) throws java.sql.SQLException {
                return wrap(database.getConnection(name,password));
            }
            java.sql.Connection wrap(java.sql.Connection connection) {
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            Object result = invoke(method,connection,args);
                            if (!method.getName().equals("prepareStatement")) return result;
                            String sql = (String) args[0];
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        Object rows = invoke(statementMethod,result,statementArgs);
                                        if (statementMethod.getName().equals("executeQuery") && sql.contains("search_projection_versions")
                                                && once.compareAndSet(false,true)) {
                                            selected.countDown(); await(resume);
                                        }
                                        return rows;
                                    });
                        });
            }
            Object invoke(java.lang.reflect.Method method,Object target,Object[] args) throws Throwable {
                try { return method.invoke(target,args); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            }
        };
        var reader = new SearchProjectionReader(JdbcClient.create(observed),new ObjectMapper());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> reader.read("TASK",id).orElseThrow());
            try {
                assertThat(selected.await(10,TimeUnit.SECONDS)).isTrue();
                tasks.updateTask(id,"Snapshot two",null,null,null,null,null,null,reporter);
            } finally { resume.countDown(); }
            var projection = reading.get(10,TimeUnit.SECONDS);
            assertThat(projection.revision()).isOne();
            assertThat(projection.document()).containsEntry("title","Snapshot one").containsEntry("_projection_revision",1L);
        }
        var current = new SearchProjectionReader(jdbc,new ObjectMapper()).read("TASK",id).orElseThrow();
        assertThat(current.revision()).isEqualTo(2);
        assertThat(current.document()).containsEntry("title","Snapshot two");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void statusRenameBeforeMembershipSerializesCreateAndStatusMove(boolean create) throws Exception {
        statusMembershipRace(create, true);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void membershipBeforeStatusRenameIsIncludedByFanout(boolean create) throws Exception {
        statusMembershipRace(create, false);
    }

    private void statusMembershipRace(boolean create, boolean renameFirst) throws Exception {
        long status = jdbc.sql("select id from ms_task_statuses where pcode='new'").query(Long.class).single();
        var id = new java.util.concurrent.atomic.AtomicLong(create ? 0 : create("moving task"));
        if (!create) {
            long other = tasks.createStatus(null, "Other", "#000000", 99, false).id();
            tasks.changeStatus(id.get(), other, reporter);
        }
        Runnable membership = () -> {
            if (create) id.set(create("joining task"));
            else tasks.changeStatus(id.get(), status, reporter);
        };
        String renamed = "Renamed " + System.nanoTime();
        Runnable rename = () -> tasks.updateStatusRecord(status, renamed, null, null, null);
        runSerialized(renameFirst ? rename : membership, renameFirst ? membership : rename);
        assertThat(jdbc.sql("select revision from search_projection_versions where entity_type='TASK' and entity_id=:id")
                .param("id", id.get()).query(Long.class).single()).isEqualTo(create ? (renameFirst ? 1L : 2L) : (renameFirst ? 3L : 4L));
        assertThat(new SearchProjectionReader(jdbc,new ObjectMapper()).read("TASK",id.get()).orElseThrow().document())
                .containsEntry("status_name",renamed);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void projectUniqueNameRenameAndTaskCreationAlreadySerializeThroughForeignKey(boolean renameFirst) throws Exception {
        long project = projects.createProject("Initial " + System.nanoTime(), "body", "A", null, reporter).id();
        Runnable membership = () -> tasks.createTask(project, null, "project member", "body", "medium", null,
                null, null, null, null, reporter);
        Runnable rename = () -> projects.updateProject(project, "Renamed " + System.nanoTime(), null, null, null);
        runSerialized(renameFirst ? rename : membership, renameFirst ? membership : rename);
    }

    private static void runSerialized(Runnable first, Runnable second) throws Exception {
        var firstDone = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var held = executor.submit(() -> tx.executeWithoutResult(status -> {
                first.run(); firstDone.countDown(); await(release);
            }));
            Future<?> contender = null;
            try {
                assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();
                contender = executor.submit(() -> tx.executeWithoutResult(status -> {
                    pid.set(jdbc.sql("select pg_backend_pid()").query(Integer.class).single());
                    secondStarted.countDown(); second.run();
                }));
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean blocked = false;
                while (!contender.isDone() && System.nanoTime() < deadline) {
                    blocked = jdbc.sql("select cardinality(pg_blocking_pids(:pid)) > 0")
                            .param("pid", pid.get()).query(Boolean.class).single();
                    if (blocked) break;
                }
                assertThat(blocked).as("second business transaction waits for parent row ownership").isTrue();
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
            if (contender != null) contender.get(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    static long create(String title) {
        return tasks.createTask(null, null, title, "body", "medium", null, null, null, null, null, reporter).id();
    }
    static long count(String table, String column, long id) {
        return jdbc.sql("select count(*) from " + table + " where " + column + "=:id").param("id", id).query(Long.class).single();
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
