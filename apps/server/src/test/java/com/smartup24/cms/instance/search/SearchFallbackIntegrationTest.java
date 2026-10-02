package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SearchFallbackIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("search_fallback_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final AtomicInteger sequence = new AtomicInteger();
    private static JdbcClient jdbc;
    private static DataSourceTransactionManager transactions;
    private static SearchFallbackRepository repository;
    private static DataSource database;
    private static ObservingDataSource observedDatabase;
    private static SearchFallbackRepository proxiedRepository;
    private static AnnotationConfigApplicationContext applicationContext;
    private TransactionStatus transaction;

    @BeforeAll
    static void setupDatabase() {
        database = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(database);
        transactions = new DataSourceTransactionManager(database);
        repository = new SearchFallbackRepository(jdbc);
        observedDatabase = new ObservingDataSource(database);
        applicationContext = new AnnotationConfigApplicationContext(TransactionProxyConfiguration.class);
        proxiedRepository = applicationContext.getBean(SearchFallbackRepository.class);
    }

    @AfterAll
    static void closeApplicationContext() {
        applicationContext.close();
    }

    @BeforeEach
    void beginTransaction() {
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
    }

    @AfterEach
    void rollback() {
        SecurityContext.clear();
        transactions.rollback(transaction);
    }

    /** The caller's scope that restricts nothing: these tests are about the queries, not the scope. */
    private static final QueryPlan.SqlFragment NO_SCOPE = new QueryPlan.SqlFragment("", Map.of());

    private static final SearchEntities ENTITIES = SearchTestEntities.unscoped();

    private static List<SearchFallbackRepository.FallbackHit> search(
            SearchFallbackRepository source, String query, String code, int limit) {
        return source.search(ENTITIES.find(code).orElseThrow(), query, List.of(query), limit, NO_SCOPE);
    }

    @Test
    void percentAndUnderscoreAreLiteralQueryCharacters() {
        long reporter = user("Reporter", "A", null);
        long literal = task("Release 100%_ready", "", reporter, null);
        task("Release 100XYready", "", reporter, null);

        assertThat(search(repository, "%_", SearchTestEntities.TASKS, 10))
                .extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(literal));
    }

    @Test
    void phoneAndProjectOrUserStateAreAppliedInPostgres() {
        long activeUser = user("Active phone", "A", "+998901234567");
        user("Pending phone", "P", "+998901234567");
        long activeProject = project("active-token", "A");
        project("active-token pending", "P");

        // An active user and a project in use: the condition and the archive of the declarations (ADR-0032, 10.3).
        assertThat(search(repository, "90123", SearchTestEntities.USERS, 10))
                .extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(activeUser));
        assertThat(search(repository, "active-token", SearchTestEntities.PROJECTS, 10))
                .extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(activeProject));
    }

    @Test
    void exactLookupUsesPositiveBigintAndCanonicalTarget() {
        long reporter = user("Exact reporter", "A", null);
        long task = task("Unrelated title", "", reporter, null);

        var hits = repository.exact(ENTITIES.find(SearchTestEntities.TASKS).orElseThrow(), task, NO_SCOPE);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(Long.toString(task));
            assertThat(hit.entityType()).isEqualTo(SearchTestEntities.TASKS);
            assertThat(hit.targetUrl()).isEqualTo("/tasks/items/" + task);
        });
    }

    @Test
    void theDatabaseCheckKeepsOnlyTheRecordsTheScopeAndTheDeclarationStillFind() {
        long reporter = user("Check reporter", "A", null);
        long kept = task("check-token kept", "", reporter, null);
        long other = task("check-token other", "", reporter, null);
        long archived = project("check-token archived", "P");
        var tasks = ENTITIES.find(SearchTestEntities.TASKS).orElseThrow();
        var projects = ENTITIES.find(SearchTestEntities.PROJECTS).orElseThrow();
        var onlyKept = new QueryPlan.SqlFragment(" and t.id = :kept_id", Map.of("kept_id", kept));

        assertThat(repository.visible(tasks, List.of(kept, other, 9_000_000_000L), NO_SCOPE))
                .containsExactlyInAnyOrder(kept, other);
        assertThat(repository.visible(tasks, List.of(kept, other), onlyKept)).containsExactly(kept);
        assertThat(repository.visible(projects, List.of(archived), NO_SCOPE)).isEmpty();
        assertThat(repository.visible(tasks, List.of(), NO_SCOPE)).isEmpty();
    }

    @Test
    void realRowsAreFairlyBudgetedAndExposeUnknownFallbackCount() {
        long reporter = user("Budget reporter", "A", null);
        task("budget-token task 1", "", reporter, null);
        task("budget-token task 2", "", reporter, null);
        task("budget-token task 3", "", reporter, null);
        project("budget-token project 1", "A");
        project("budget-token project 2", "A");
        user("budget-token user", "A", null);
        TypesenseSearch typesense = mock(TypesenseSearch.class);
        when(typesense.isEnabled()).thenReturn(false);
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                999L, "admin", "admin@example.invalid", 1L, false, Set.of("*.*"), 1, false, 0, null));
        SearchService service = SearchAccessFixtures.service(
                typesense,
                jdbc,
                new SearchPolicyProvider(
                        new SearchOwnerRateLimits() {
                            @Override
                            public int userPerMinute() {
                                return 600;
                            }

                            @Override
                            public int tokenPerMinute() {
                                return 300;
                            }
                        },
                        new SearchSettingsRepository(jdbc)),
                new SearchExecutionSnapshotReader(new SearchIndexStateRepository(jdbc, ENTITIES)),
                ENTITIES);

        var result = service.search("budget-token", "ALL", 4);

        // A hit of each entity in turn, in the order of their codes (ADR-0032, 10.3).
        assertThat(result.hits())
                .extracting(SearchHit::entityType)
                .containsExactly("md.users", "ms.projects", "ms.projects", "ms.tasks");
        assertThat(result.totalHits()).isEqualTo(4);
        assertThat(result.foundHits()).isNull();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void eachFallbackGroupUsesOneExtraRowToDeriveHasMore() {
        long reporter = user("Page reporter", "A", null);
        task("page-token 1", "", reporter, null);
        task("page-token 2", "", reporter, null);
        task("page-token 3", "", reporter, null);

        assertThat(search(repository, "page-token", SearchTestEntities.TASKS, 3))
                .hasSize(3);
        assertThat(search(repository, "page-token", SearchTestEntities.TASKS, 2))
                .hasSize(2);
    }

    @Test
    void taskSearchFindsTheTitleAndTheTextOfTheSpecTheTitleFirst() {
        long reporter = user("Context reporter", "A", null);
        long byText = task("ordinary", "the search-text-context here", reporter, null);
        long byTitle = task("search-text-context in the title", "", reporter, null);

        assertThat(search(repository, "search-text-context", SearchTestEntities.TASKS, 10))
                .extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(byTitle), Long.toString(byText));
    }

    @Test
    void springProxyRunsFallbackInAReadOnlyPostgresTransaction() {
        observedDatabase.clearObservation();

        var result = search(proxiedRepository, "read-only-probe", SearchTestEntities.TASKS, 10);

        assertThat(AopUtils.isAopProxy(proxiedRepository)).isTrue();
        assertThat(result).isEmpty();
        assertThat(observedDatabase.transactionReadOnly()).isEqualTo("on");
    }

    @Test
    void springProxyCancelsBlockedFallbackWithinItsTwoSecondQueryBudget() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = database.getConnection();
                Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("lock table ms_tasks in access exclusive mode");

            var future = executor.submit(() -> {
                long started = System.nanoTime();
                try {
                    search(proxiedRepository, "lock-budget-probe", SearchTestEntities.TASKS, 10);
                    return new TimedFailure(null, elapsedMillis(started));
                } catch (Throwable failure) {
                    return new TimedFailure(failure, elapsedMillis(started));
                }
            });

            TimedFailure failure;
            try {
                failure = future.get(6, TimeUnit.SECONDS);
            } finally {
                blocker.rollback();
            }

            assertThat(failure.failure()).isNotNull();
            assertThat(rootCause(failure.failure()))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            sql -> assertThat(sql.getSQLState()).isEqualTo("57014"));
            assertThat(failure.elapsedMillis()).isBetween(1_000L, 5_000L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static long user(String name, String state, String phone) {
        int number = sequence.incrementAndGet();
        return jdbc.sql("""
                insert into md_users (name, login, email, phone, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change)
                values (:name, :login, :email, :phone, 'x', :state, 'ru', 'UTC', '{}', false, false)
                returning id
                """)
                .param("name", name)
                .param("login", "search-user-" + number)
                .param("email", "search-user-" + number + "@example.invalid")
                .param("phone", phone)
                .param("state", state)
                .query(Long.class)
                .single();
    }

    /** A project in use ({@code A}) or in the archive ({@code P}, the paused state of before ADR-0032, 5.4). */
    private static long project(String name, String state) {
        return jdbc.sql("insert into ms_task_projects (name, archived_at) values (:name,"
                        + " case when :state = 'P' then clock_timestamp() end) returning id")
                .param("name", name + "-" + sequence.incrementAndGet())
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private static long task(String title, String description, long reporter, Long project) {
        return jdbc.sql("""
                insert into ms_tasks (project_id, title, description_markdown, priority,
                                      reporter_id, attributes, created_by, modified_by)
                values (:project, :title, :description, 'medium', :reporter, '{}', :reporter, :reporter)
                returning id
                """)
                .param("project", project)
                .param("title", title)
                .param("description", description)
                .param("reporter", reporter)
                .query(Long.class)
                .single();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionProxyConfiguration {
        @Bean
        DataSource dataSource() {
            return observedDatabase;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SearchFallbackRepository searchFallbackRepository(JdbcClient jdbcClient) {
            return new SearchFallbackRepository(jdbcClient);
        }
    }

    private static final class ObservingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicReference<String> transactionReadOnly = new AtomicReference<>();

        private ObservingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(delegate.getConnection(username, password));
        }

        String transactionReadOnly() {
            return transactionReadOnly.get();
        }

        void clearObservation() {
            transactionReadOnly.set(null);
        }

        private Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement") && transactionReadOnly.get() == null) {
                            try (Statement statement = connection.createStatement();
                                    ResultSet result = statement.executeQuery("show transaction_read_only")) {
                                result.next();
                                transactionReadOnly.compareAndSet(null, result.getString(1));
                            }
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    private record TimedFailure(Throwable failure, long elapsedMillis) {}
}
