package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryListRegistry;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdCustomFieldRepository;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository.LegacyUserFilters;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldQueryFields;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Custom fields as registry fields (ADR-0019 2.3, roadmap item 52), on the user list. */
@Testcontainers
class MdCustomFieldQueryFieldsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_custom_fields_registry_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdCustomFieldService fields;
    static QueryListRegistry registry;
    static MdUserListService users;
    static Long viewer;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var audit = new AuditLogService(new AuditLogRepository(jdbc, mapper), null, new AuditDataRedactor());
        var repository = new MdCustomFieldRepository(jdbc, mapper);
        fields = new MdCustomFieldService(repository, audit);
        registry = new QueryListRegistry(List.of(MdUserQuery.LIST), List.of(new MdCustomFieldQueryFields(repository, fields)));
        var roles = new MdRoleRepository(jdbc);
        var scope = new MdScopeService(new MdScopeRepository(jdbc), new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)), audit);
        users = new MdUserListService(new QueryListRepository(jdbc), new MdUserRepository(jdbc, mapper), scope, roles,
                registry);

        fields.createField("USER", "cfr_region", "Регион", "string", false, null, null, 1);
        fields.createField("USER", "cfr_grade", "Грейд", "number", false, null, null, 2);
        fields.createField("USER", "cfr_shift", "Смена", "select", false, null, List.of("day", "night"), 3);
        fields.createField("USER", "cfr_remote", "Удалённо", "boolean", false, null, null, 4);
        fields.createField("USER", "cfr_hired", "Принят", "date", false, null, null, 5);

        viewer = user("cfr_viewer", "{}");
        roles.assignRolesToUser(viewer, List.of(roles.findByPcode("admin").orElseThrow().id()));
        scope.recalculateFor(viewer);
        user("cfr_anna", "{\"cfr_region\":\"Tashkent\",\"cfr_grade\":5,\"cfr_shift\":\"day\",\"cfr_remote\":true,\"cfr_hired\":\"2024-03-01\"}");
        user("cfr_bek", "{\"cfr_region\":\"Samarkand\",\"cfr_grade\":\"12\",\"cfr_shift\":\"night\",\"cfr_remote\":false,\"cfr_hired\":\"2026-01-15\"}");
        // Written before validation existed: shapes the fields do not accept.
        user("cfr_old", "{\"cfr_grade\":\"a lot\",\"cfr_remote\":\"maybe\",\"cfr_hired\":\"soon\"}");
    }

    @Test
    @DisplayName("Each custom field is a registry field: its own label, its attribute, a filter, never a sort")
    void customFieldsAreRegistryFields() {
        var list = registry.resolve(MdUserQuery.LIST);
        QueryField region = list.field("cfCfrRegion").orElseThrow();
        assertThat(region.label()).isEqualTo("Регион");
        assertThat(region.attribute()).isEqualTo("cfr_region");
        assertThat(region.type()).isEqualTo(QueryFieldType.TEXT);
        assertThat(region.searchable()).isTrue();
        assertThat(list.field("cfCfrGrade").orElseThrow().type()).isEqualTo(QueryFieldType.NUMBER);
        assertThat(list.field("cfCfrShift").orElseThrow().enumValues()).containsExactly("day", "night");
        assertThat(list.fields()).filteredOn(field -> field.attribute() != null).noneMatch(QueryField::sortable);
        assertThat(MdUserQuery.LIST.field("cfCfrRegion")).as("the declared list stays as the code wrote it").isEmpty();
    }

    @Test
    @DisplayName("Custom fields filter by type; values of the wrong shape read as empty instead of failing")
    void filtersByType() {
        assertThat(logins("[{\"field\":\"cfCfrRegion\",\"op\":\"eq\",\"value\":\"Tashkent\"}]")).containsExactly("cfr_anna");
        assertThat(logins("[{\"field\":\"cfCfrGrade\",\"op\":\"gt\",\"value\":10}]")).containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrShift\",\"op\":\"in\",\"value\":[\"night\"]}]")).containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrRemote\",\"op\":\"eq\",\"value\":true}]")).containsExactly("cfr_anna");
        assertThat(logins("[{\"field\":\"cfCfrHired\",\"op\":\"gte\",\"value\":\"2025-01-01\"}]")).containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrGrade\",\"op\":\"empty\"}]")).contains("cfr_old", "cfr_viewer")
                .doesNotContain("cfr_anna", "cfr_bek");
    }

    @Test
    @DisplayName("Free search looks in text custom fields; sorting by a custom field is refused")
    void searchAndSort() {
        assertThat(users.pageViews(viewer, null, null, null, null, "samark", LegacyUserFilters.none()).items())
                .extracting(MdUserView::login).containsExactly("cfr_bek");
        assertThatThrownBy(() -> users.page(viewer, null, null, null, "cfCfrGrade", null, LegacyUserFilters.none()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.SORT_INVALID));
    }

    @Test
    @DisplayName("A field added by an administrator appears in the list at once, without a release")
    void newFieldAppearsAtOnce() {
        assertThat(registry.resolve(MdUserQuery.LIST).field("cfCfrDesk")).isEmpty();
        fields.createField("USER", "cfr_desk", "Стол", "string", false, null, null, 9);
        assertThat(registry.resolve(MdUserQuery.LIST).field("cfCfrDesk")).isPresent();
    }

    @Test
    @DisplayName("Changing a field's options and default is audited with the values before and after")
    void optionsChangeIsAudited() {
        var field = fields.createField("USER", "cfr_level", "Уровень", "select", false, null, List.of("a", "b"), 10);
        fields.updateField(field.id(), null, null, "b", List.of("a", "b", "c"), null);

        var row = jdbc.sql("""
                        select old_row::text as old_row, new_row::text as new_row from audit_log
                        where table_name = 'md_custom_fields' and row_pk = :id and event = 'U'
                        order by id desc limit 1
                        """)
                .param("id", String.valueOf(field.id()))
                .query((rs, n) -> List.of(rs.getString("old_row"), rs.getString("new_row"))).single();
        assertThat(row.get(0)).contains("\"options_json\"").doesNotContain("\\\"c\\\"");
        assertThat(row.get(1)).contains("\"default_value\": \"b\"").contains("\\\"c\\\"");
    }

    private static List<String> logins(String filter) {
        return users.pageViews(viewer, null, null, filter, "login", "cfr_", LegacyUserFilters.none()).items().stream()
                .map(MdUserView::login).toList();
    }

    private static Long user(String login, String attributes) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC', cast(:attributes as jsonb),
                                false, false)
                        returning id
                        """)
                .param("login", login).param("attributes", attributes).query(Long.class).single();
    }
}
