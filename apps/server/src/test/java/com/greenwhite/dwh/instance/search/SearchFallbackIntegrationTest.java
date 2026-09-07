package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.common.security.RoleMembershipAuthorizer;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository.FallbackSearch;
import com.greenwhite.dwh.instance.search.service.SearchAccessPolicy;
import com.greenwhite.dwh.instance.search.service.SearchResultBudget;
import com.greenwhite.dwh.instance.search.service.SearchService;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class SearchFallbackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("search_fallback_test").withUsername("test_user").withPassword("test_pass");

    private static final AtomicInteger sequence = new AtomicInteger();
    private static JdbcClient jdbc;
    private static DataSourceTransactionManager transactions;
    private static SearchFallbackRepository repository;
    private TransactionStatus transaction;

    @BeforeAll
    static void setupDatabase() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        repository = new SearchFallbackRepository(jdbc);
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

    @Test
    void percentAndUnderscoreAreLiteralQueryCharacters() {
        long reporter = user("Reporter", "A", null);
        long literal = task("Release 100%_ready", "", reporter, null);
        task("Release 100XYready", "", reporter, null);

        FallbackSearch result = repository.search("%_", "TASK", 10);

        assertThat(result.groups()).singleElement().satisfies(group ->
                assertThat(group.hits()).extracting(SearchFallbackRepository.FallbackHit::id)
                        .containsExactly(Long.toString(literal)));
    }

    @Test
    void phoneAndProjectOrUserStateAreAppliedInPostgres() {
        long activeUser = user("Active phone", "A", "+998-90-123-45-67");
        user("Pending phone", "P", "+998-90-123-45-67");
        long activeProject = project("active-token", "A");
        project("active-token pending", "P");

        FallbackSearch users = repository.search("90-123", "USER", 10);
        FallbackSearch projects = repository.search("active-token", "PROJECT", 10);

        assertThat(users.groups().getFirst().hits()).extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(activeUser));
        assertThat(projects.groups().getFirst().hits()).extracting(SearchFallbackRepository.FallbackHit::id)
                .containsExactly(Long.toString(activeProject));
    }

    @Test
    void exactLookupUsesPositiveBigintAndCanonicalTarget() {
        long reporter = user("Exact reporter", "A", null);
        long task = task("Unrelated title", "", reporter, null);

        FallbackSearch result = repository.searchExact(task, "TASK");

        assertThat(result.groups().getFirst().hits()).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(Long.toString(task));
            assertThat(hit.targetUrl()).isEqualTo("/tasks/items/" + task);
        });
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
        TypesenseClient typesense = mock(TypesenseClient.class);
        when(typesense.isEnabled()).thenReturn(false);
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                999L, "admin", "admin@example.invalid", 1L, false, Set.of("*.*"), 1, false, 0, null));
        SearchService service = new SearchService(typesense, repository,
                new SearchAccessPolicy(mock(RoleMembershipAuthorizer.class)), new SearchResultBudget());

        var result = service.search("budget-token", "ALL", 4);

        assertThat(result.hits()).extracting(SearchHit::entityType)
                .containsExactly("TASK", "TASK", "PROJECT", "USER");
        assertThat(result.totalHits()).isEqualTo(4);
        assertThat(result.foundHits()).isNull();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void eachFallbackGroupUsesOneExtraRowToDeriveHasMore() {
        long reporter = user("Page reporter", "A", null);
        long first = task("page-token 1", "", reporter, null);
        long second = task("page-token 2", "", reporter, null);
        task("page-token 3", "", reporter, null);

        FallbackSearch result = repository.search("page-token", "TASK", 2);

        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.hits()).extracting(SearchFallbackRepository.FallbackHit::id)
                    .containsExactly(Long.toString(first), Long.toString(second));
            assertThat(group.hasMore()).isTrue();
        });
    }

    @Test
    void taskSearchIncludesProjectAndStatusText() {
        long reporter = user("Context reporter", "A", null);
        long project = project("search-project-context", "A");
        long byProject = task("ordinary", "", reporter, project);
        String status = "Status " + sequence.incrementAndGet();
        jdbc.sql("update ms_task_statuses set name = :name where id = (select status_id from ms_tasks where id = :id)")
                .param("name", status).param("id", byProject).update();

        assertThat(repository.search("search-project-context", "TASK", 10).groups().getFirst().hits())
                .extracting(SearchFallbackRepository.FallbackHit::id).containsExactly(Long.toString(byProject));
        assertThat(repository.search(status, "TASK", 10).groups().getFirst().hits())
                .extracting(SearchFallbackRepository.FallbackHit::id).containsExactly(Long.toString(byProject));
    }

    private static long user(String name, String state, String phone) {
        int number = sequence.incrementAndGet();
        return jdbc.sql("""
                insert into md_users (name, login, email, phone, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change)
                values (:name, :login, :email, :phone, 'x', :state, 'ru', 'UTC', '{}', false, false)
                returning id
                """).param("name", name).param("login", "search-user-" + number)
                .param("email", "search-user-" + number + "@example.invalid")
                .param("phone", phone).param("state", state).query(Long.class).single();
    }

    private static long project(String name, String state) {
        return jdbc.sql("insert into ms_task_projects (name, state) values (:name, :state) returning id")
                .param("name", name + "-" + sequence.incrementAndGet()).param("state", state)
                .query(Long.class).single();
    }

    private static long task(String title, String description, long reporter, Long project) {
        return jdbc.sql("""
                insert into ms_tasks (project_id, title, description_markdown, status_id, priority,
                                      reporter_id, attributes, created_by, modified_by)
                values (:project, :title, :description,
                        (select id from ms_task_statuses order by id limit 1), 'medium',
                        :reporter, '{}', :reporter, :reporter)
                returning id
                """).param("project", project).param("title", title).param("description", description)
                .param("reporter", reporter).query(Long.class).single();
    }
}
