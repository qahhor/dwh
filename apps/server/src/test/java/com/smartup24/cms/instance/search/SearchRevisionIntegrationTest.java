package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.ms.task.MsTaskFixture;
import com.smartup24.cms.instance.search.repository.SearchProjectionReader;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.typesense.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class SearchRevisionIntegrationTest {
    @Test
    void oversizedProjectionFailsBeforeMaterializingAndCannotBecomeATombstone() {
        long id = create("large projection");
        jdbc.sql("update ms_tasks set description_markdown=repeat('x',1048576) where id=:id")
                .param("id", id)
                .update();
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper(), ENTITIES);
        assertThatThrownBy(() -> reader.read(SearchTestEntities.TASKS, id)).hasMessage("DOCUMENT_TOO_LARGE");
    }

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("search_revisions")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static MsTaskFixture tasks;
    static SearchChangePublisher publisher;
    static long reporter;
    static DriverManagerDataSource database;
    static final SearchEntities ENTITIES = SearchTestEntities.unscoped();

    @BeforeAll
    static void setup() {
        database = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(database)
                .load()
                .migrate();
        jdbc = JdbcClient.create(database);
        var manager = new DataSourceTransactionManager(database);
        tx = new TransactionTemplate(manager);
        publisher = proxied(new SearchChangePublisher(jdbc), manager);
        tasks = new MsTaskFixture(jdbc, publisher, tx);
        reporter = jdbc.sql("""
                insert into md_users(name,login,email,password_hash,state,language,timezone)
                values ('Reporter','revision-reporter','revision@example.invalid','x','A','ru','UTC') returning id
                """).query(Long.class).single();
    }

    @Test
    void taskAndRevisionBecomeVisibleTogetherOnlyAfterCommit() throws Exception {
        var inserted = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var id = new AtomicLong();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var held = executor.submit(() -> tx.executeWithoutResult(status -> {
                id.set(create("committed task"));
                inserted.countDown();
                await(finish);
            }));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(count("ms_tasks", "id", id.get())).isZero();
                assertThat(count("search_projection_versions", "entity_id", id.get()))
                        .isZero();
            } finally {
                finish.countDown();
            }
            held.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("ms_tasks", "id", id.get())).isOne();
        assertThat(count("search_projection_versions", "entity_id", id.get())).isOne();
    }

    @Test
    void rollbackRemovesBusinessRowAndRevision() {
        long id = tx.execute(status -> {
            long created = create("rolled back task");
            assertThat(count("search_projection_versions", "entity_id", created))
                    .isOne();
            status.setRollbackOnly();
            return created;
        });
        assertThat(count("ms_tasks", "id", id)).isZero();
        assertThat(count("search_projection_versions", "entity_id", id)).isZero();
    }

    @Test
    void publisherRejectsCallsOutsideBusinessTransaction() {
        assertThatThrownBy(() -> publisher.changed(SearchTestEntities.TASKS, 99))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void projectRenameReindexesTheProjectAtomicallyAndRollbackRestoresIt() throws Exception {
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper(), ENTITIES);
        long project = createProject("Before " + System.nanoTime());
        long task = tasks.create("Child", reporter, project, null);
        var beforeProject = reader.read(SearchTestEntities.PROJECTS, project).orElseThrow();
        var beforeTask = reader.read(SearchTestEntities.TASKS, task).orElseThrow();
        tx.executeWithoutResult(transaction -> {
            renameProject(project, "Uncommitted " + project);
            assertThat(reader.read(SearchTestEntities.PROJECTS, project)
                            .orElseThrow()
                            .revision())
                    .isEqualTo(2);
            transaction.setRollbackOnly();
        });
        assertThat(reader.read(SearchTestEntities.PROJECTS, project).orElseThrow())
                .isEqualTo(beforeProject);
        renameProject(project, "Committed " + project);
        assertThat(reader.read(SearchTestEntities.PROJECTS, project)
                        .orElseThrow()
                        .document())
                .containsEntry("name", "Committed " + project);
        // A task's document holds only its own fields (ADR-0032, 10.3): a renamed project leaves it as it was.
        assertThat(reader.read(SearchTestEntities.TASKS, task).orElseThrow()).isEqualTo(beforeTask);
    }

    @Test
    void theDocumentsCarryTheScopeKeysOfTheirRecords() {
        // ADR-0032, 10.3: the users whose own rule sees the record, and the units of those users.
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper(), ENTITIES);
        long unit = jdbc.sql("""
                        insert into md_org_units (parent_id, code, name, kind, state, order_no)
                        values (null, :code, 'Scope keys', 'department', 'A', 1) returning id
                        """)
                .param("code", "scope-keys-" + System.nanoTime())
                .query(Long.class)
                .single();
        long member = jdbc.sql("""
                        insert into md_users(name,login,email,password_hash,state,language,timezone,org_unit_id)
                        values ('Member',:login,:login || '@example.invalid','x','A','ru','UTC',:unit) returning id
                        """)
                .param("login", "scope-keys-" + System.nanoTime())
                .param("unit", unit)
                .query(Long.class)
                .single();
        long project = createProject("Keys " + System.nanoTime());
        long task = tasks.create("Keys", reporter, project, null);
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind, is_viewed) values (:task, :user, 'E',"
                        + " false)")
                .param("task", task)
                .param("user", member)
                .update();
        var document = reader.read(SearchTestEntities.TASKS, task).orElseThrow().document();
        assertThat(document).containsEntry("record_id", (int) task);
        assertThat((java.util.List<?>) document.get("scope_users"))
                .map(value -> ((Number) value).longValue())
                .containsExactly(reporter, member);
        assertThat((java.util.List<?>) document.get("scope_units"))
                .map(value -> ((Number) value).longValue())
                .containsExactly(unit);
        var projectDocument =
                reader.read(SearchTestEntities.PROJECTS, project).orElseThrow().document();
        assertThat((java.util.List<?>) projectDocument.get("scope_users"))
                .map(value -> ((Number) value).longValue())
                .containsExactly(reporter, member);
    }

    @Test
    void fingerprintIgnoresRevisionAndExcludedOrMissingSourceHasStableTombstone() {
        var reader = new SearchProjectionReader(jdbc, new ObjectMapper(), ENTITIES);
        long project = createProject("Fingerprint " + System.nanoTime());
        assertThat(reader.read(SearchTestEntities.PROJECTS, project)).isPresent();
        var first = reader.read(SearchTestEntities.PROJECTS, project).orElseThrow();
        tx.executeWithoutResult(status -> publisher.changed(SearchTestEntities.PROJECTS, project));
        var next = reader.read(SearchTestEntities.PROJECTS, project).orElseThrow();
        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.fingerprint()).matches("[0-9a-f]{64}").isEqualTo(first.fingerprint());
        assertThat(next.document())
                .containsEntry("_projection_revision", 2L)
                .containsEntry("_projection_fingerprint", next.fingerprint());
        archiveProject(project);
        var excluded = reader.read(SearchTestEntities.PROJECTS, project).orElseThrow();
        assertThat(excluded.document()).isNull();
        tx.executeWithoutResult(status -> {
            jdbc.sql("delete from ms_task_projects where id=:id")
                    .param("id", project)
                    .update();
            publisher.changed(SearchTestEntities.PROJECTS, project);
        });
        var missing = reader.read(SearchTestEntities.PROJECTS, project).orElseThrow();
        assertThat(missing.document()).isNull();
        assertThat(missing.fingerprint()).isEqualTo(excluded.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(reader.read(SearchTestEntities.TASKS, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void readerKeepsBodyAndRevisionFromTheSameStatementWhenCommitOccursDuringResultConsumption() throws Exception {
        long id = create("Snapshot one");
        var selected = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var observed = new AbstractDataSource() {
            final AtomicBoolean once = new AtomicBoolean();

            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return wrap(database.getConnection());
            }

            @Override
            public java.sql.Connection getConnection(String name, String password) throws java.sql.SQLException {
                return wrap(database.getConnection(name, password));
            }

            java.sql.Connection wrap(java.sql.Connection connection) {
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(method, connection, args);
                            if (!method.getName().equals("prepareStatement")) return result;
                            String sql = (String) args[0];
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {java.sql.PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        Object rows = invoke(statementMethod, result, statementArgs);
                                        if (statementMethod.getName().equals("executeQuery")
                                                && sql.contains("search_projection_versions")
                                                && once.compareAndSet(false, true)) {
                                            selected.countDown();
                                            await(resume);
                                        }
                                        return rows;
                                    });
                        });
            }

            Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
                try {
                    return method.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        };
        var reader = new SearchProjectionReader(JdbcClient.create(observed), new ObjectMapper(), ENTITIES);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(
                    () -> reader.read(SearchTestEntities.TASKS, id).orElseThrow());
            try {
                assertThat(selected.await(10, TimeUnit.SECONDS)).isTrue();
                tasks.rename(id, "Snapshot two", reporter);
            } finally {
                resume.countDown();
            }
            var projection = reading.get(10, TimeUnit.SECONDS);
            assertThat(projection.revision()).isOne();
            assertThat(projection.document())
                    .containsEntry("title", "Snapshot one")
                    .containsEntry("_projection_revision", 1L);
        }
        var current = new SearchProjectionReader(jdbc, new ObjectMapper(), ENTITIES)
                .read(SearchTestEntities.TASKS, id)
                .orElseThrow();
        assertThat(current.revision()).isEqualTo(2);
        assertThat(current.document()).containsEntry("title", "Snapshot two");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void projectUniqueNameRenameAndTaskCreationAlreadySerializeThroughForeignKey(boolean renameFirst) throws Exception {
        long project = createProject("Initial " + System.nanoTime());
        Runnable membership = () -> tasks.create("project member", reporter, project, null);
        Runnable rename = () -> renameProject(project, "Renamed " + System.nanoTime());
        runSerialized(renameFirst ? rename : membership, renameFirst ? membership : rename);
    }

    private static void runSerialized(Runnable first, Runnable second) throws Exception {
        var firstDone = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var held = executor.submit(() -> tx.executeWithoutResult(status -> {
                first.run();
                firstDone.countDown();
                await(release);
            }));
            Future<?> contender = null;
            try {
                assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();
                contender = executor.submit(() -> tx.executeWithoutResult(status -> {
                    pid.set(jdbc.sql("select pg_backend_pid()")
                            .query(Integer.class)
                            .single());
                    secondStarted.countDown();
                    second.run();
                }));
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean blocked = false;
                while (!contender.isDone() && System.nanoTime() < deadline) {
                    blocked = jdbc.sql("select cardinality(pg_blocking_pids(:pid)) > 0")
                            .param("pid", pid.get())
                            .query(Boolean.class)
                            .single();
                    if (blocked) break;
                }
                assertThat(blocked)
                        .as("second business transaction waits for parent row ownership")
                        .isTrue();
            } finally {
                release.countDown();
            }
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
        return tasks.create(title, reporter);
    }

    static long count(String table, String column, long id) {
        return jdbc.sql("select count(*) from " + table + " where " + column + "=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A project created the way the entity runtime creates it (ADR-0032, 6.3): the row, then the project hook. */
    private static long createProject(String name) {
        return java.util.Objects.requireNonNull(tx.execute(transaction -> {
            long id = jdbc.sql("insert into ms_task_projects (name, description, created_by, modified_by)"
                            + " values (:name, 'body', :user, :user) returning id")
                    .param("name", name)
                    .param("user", reporter)
                    .query(Long.class)
                    .single();
            publisher.changed(SearchTestEntities.PROJECTS, id);
            return id;
        }));
    }

    /** A project renamed as the runtime renames it: read for update, written with its revision, then the hook. */
    private static void renameProject(long project, String name) {
        tx.executeWithoutResult(transaction -> {
            jdbc.sql("select id from ms_task_projects where id = :id for update")
                    .param("id", project)
                    .query(Long.class)
                    .single();
            jdbc.sql("update ms_task_projects set name = :name, revision = revision + 1 where id = :id")
                    .param("name", name)
                    .param("id", project)
                    .update();
            publisher.changed(SearchTestEntities.PROJECTS, project);
        });
    }

    /** A project archived as the runtime archives it (ADR-0032, 5.4): the hook, then the switch. */
    private static void archiveProject(long project) {
        tx.executeWithoutResult(transaction -> {
            publisher.changed(SearchTestEntities.PROJECTS, project);
            jdbc.sql("update ms_task_projects set archived_at = clock_timestamp(), revision = revision + 1"
                            + " where id = :id")
                    .param("id", project)
                    .update();
        });
    }
}
