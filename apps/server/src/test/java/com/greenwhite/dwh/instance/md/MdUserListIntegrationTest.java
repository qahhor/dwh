package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository.LegacyUserFilters;
import com.greenwhite.dwh.instance.md.service.MdListExporters;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.md.service.MdUserListService;
import com.greenwhite.dwh.instance.md.service.MdUserQuery;
import com.greenwhite.dwh.instance.md.service.MdUserView;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The user list on the field registry (ADR-0016, roadmap item 48), with the flat filters it took before. */
@Testcontainers
class MdUserListIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_user_list_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdScopeService scopeService;
    static MdRoleRepository roleRepository;
    static MdUserListService users;
    static Long viewer;
    static Long roleId;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        var audit = new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        roleRepository = new MdRoleRepository(jdbc);
        scopeService = new MdScopeService(new MdScopeRepository(jdbc), new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)), audit);
        users = new MdUserListService(new QueryListRepository(jdbc), new MdUserRepository(jdbc, new ObjectMapper()),
                scopeService, roleRepository);

        viewer = createUser("lst_viewer", "Viewer", "A", false, null);
        roleId = roleRepository.findByPcode("admin").orElseThrow().id();
        roleRepository.assignRolesToUser(viewer, List.of(roleId));
        scopeService.recalculateFor(viewer);

        createUser("lst_carol", "Carol", "A", true, "+998900000003");
        createUser("lst_alice", "Alice", "A", false, "+998900000001");
        createUser("lst_bob", "Bob", "P", false, "+998900000002");
        Long dave = createUser("lst_dave", "Dave", "A", false, null);
        roleRepository.assignRolesToUser(dave, List.of(roleId));
    }

    @Test
    @DisplayName("Sorted by name by default, 20 a page, with the real total")
    void sortedByNameWithTheTotal() {
        var page = users.page(viewer, null, null, null, null, "lst_", LegacyUserFilters.none());
        assertThat(names(page.items())).containsExactly("Alice", "Bob", "Carol", "Dave", "Viewer");
        assertThat(page.totalEstimated()).isEqualTo(5);

        var byLogin = users.page(viewer, null, null, null, "-login", "lst_", LegacyUserFilters.none());
        assertThat(names(byLogin.items())).containsExactly("Viewer", "Dave", "Carol", "Bob", "Alice");
    }

    @Test
    @DisplayName("Search q looks in name, login, email and phone")
    void searchLooksInEveryTextField() {
        assertThat(names(users.page(viewer, null, null, null, null, "+99890000000", LegacyUserFilters.none()).items()))
                .containsExactly("Alice", "Bob", "Carol");
        assertThat(names(users.page(viewer, null, null, null, null, "lst_bob@", LegacyUserFilters.none()).items()))
                .containsExactly("Bob");
    }

    @Test
    @DisplayName("The DSL and the old flat filters narrow the same list")
    void dslAndFlatFiltersNarrowTheList() {
        assertThat(names(users.page(viewer, null, null, "[{\"field\":\"state\",\"op\":\"eq\",\"value\":\"P\"}]", null,
                "lst_", LegacyUserFilters.none()).items())).containsExactly("Bob");
        assertThat(names(users.page(viewer, null, null, null, null, "lst_",
                new LegacyUserFilters("P", null, null, null)).items())).containsExactly("Bob");
        assertThat(names(users.page(viewer, null, null, null, null, "lst_",
                new LegacyUserFilters(null, null, null, true)).items())).containsExactly("Carol");
        assertThat(names(users.page(viewer, null, null, null, null, "lst_",
                new LegacyUserFilters(null, roleId, null, null)).items())).containsExactly("Dave", "Viewer");
    }

    @Test
    @DisplayName("A cursor continues only the same filters, including the flat ones")
    void cursorBelongsToItsFilters() {
        var first = users.page(viewer, 2, null, null, null, "lst_", LegacyUserFilters.none());
        assertThat(first.hasMore()).isTrue();
        var second = users.page(viewer, 2, first.nextCursor(), null, null, "lst_", LegacyUserFilters.none());
        assertThat(names(second.items())).containsExactly("Carol", "Dave");

        assertThatThrownBy(() -> users.page(viewer, 2, first.nextCursor(), null, null, "lst_",
                new LegacyUserFilters("A", null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.INVALID_CURSOR));
    }

    @Test
    @DisplayName("The page size is 1 to 200")
    void pageSizeIsBounded() {
        assertThatThrownBy(() -> users.page(viewer, 201, null, null, null, null, LegacyUserFilters.none()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.INVALID_LIMIT));
    }

    @Test
    @DisplayName("Views carry roles and no password hash; every registry field is a view property")
    void viewsCarryRolesAndEveryField() {
        var page = users.pageViews(viewer, null, null, null, null, "lst_dave", LegacyUserFilters.none());
        assertThat(page.items()).singleElement().satisfies(view -> assertThat(view.roleIds()).containsExactly(roleId));

        var properties = java.util.Arrays.stream(MdUserView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(MdUserQuery.LIST.fields()).allSatisfy(field -> assertThat(properties).contains(field.key()));
    }

    @Test
    @DisplayName("The export refuses bad option values before the job starts")
    void exportChecksOptionValues() {
        var exporter = new MdListExporters().iamUsersExporter(users);
        assertThat(exporter.checkOptions(Map.of("role_id", "12", "state", "A", "is_2fa_enabled", "true"))).isEmpty();
        assertThat(exporter.checkOptions(Map.of("role_id", "x", "state", "Z", "is_2fa_enabled", "yes")))
                .extracting(FieldErrorItem::field).containsExactlyInAnyOrder("role_id", "state", "is_2fa_enabled");
    }

    private static List<String> names(List<?> items) {
        return items.stream().map(item -> item instanceof MdUserView view ? view.name()
                : ((MdUserRepository.UserRecord) item).name()).toList();
    }

    private static Long createUser(String login, String name, String state, boolean twoFactor, String phone) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, phone, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:name, :login, :login || '@test.local', :phone, 'x', :state, 'ru', 'UTC',
                                '{}'::jsonb, :twoFactor, false)
                        returning id
                        """)
                .param("name", name)
                .param("login", login)
                .param("phone", phone)
                .param("state", state)
                .param("twoFactor", twoFactor)
                .query(Long.class).single();
    }
}
