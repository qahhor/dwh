package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityRowMapper;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.service.MdCustomFieldQueryFields;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

/** Custom fields as registry fields (ADR-0019 2.3, roadmap item 52), on the list of the user entity (ADR-0032, 8). */
class MdCustomFieldQueryFieldsIntegrationTest {

    /** The user entity, every row visible: the data scope is not what this test is about. */
    static final EntityDefinition USERS =
            MdUserEntity.definition((userId, alias) -> EntityScope.Condition.unrestricted());

    static final QueryList LIST = EntityLists.queryList(USERS);

    static DataSource ds;
    static JdbcClient jdbc;
    static ObjectMapper mapper;
    static MdCustomFieldService fields;
    static QueryListRegistry registry;

    @BeforeAll
    static void setup() {
        ds = TestDatabases.migratedCopy("smc_custom_fields_registry_test");
        jdbc = JdbcClient.create(ds);
        mapper = new ObjectMapper();
        var audit = new AuditLogService(new AuditLogRepository(jdbc, mapper), null, new AuditDataRedactor());
        var repository = new MdCustomFieldRepository(jdbc, mapper);
        fields = new MdCustomFieldService(repository, audit);
        registry = new QueryListRegistry(List.of(LIST), List.of(), List.of(new MdCustomFieldQueryFields(fields)));

        fields.createField("USER", "cfr_region", "Регион", "string", false, null, null, 1);
        fields.createField("USER", "cfr_grade", "Грейд", "number", false, null, null, 2);
        fields.createField("USER", "cfr_shift", "Смена", "select", false, null, List.of("day", "night"), 3);
        fields.createField("USER", "cfr_remote", "Удалённо", "boolean", false, null, null, 4);
        fields.createField("USER", "cfr_hired", "Принят", "date", false, null, null, 5);

        user("cfr_viewer", "{}");
        user(
                "cfr_anna",
                "{\"cfr_region\":\"Tashkent\",\"cfr_grade\":5,\"cfr_shift\":\"day\",\"cfr_remote\":true,\"cfr_hired\":\"2024-03-01\"}");
        user(
                "cfr_bek",
                "{\"cfr_region\":\"Samarkand\",\"cfr_grade\":\"12\",\"cfr_shift\":\"night\",\"cfr_remote\":false,\"cfr_hired\":\"2026-01-15\"}");
        // Written before validation existed: shapes the fields do not accept.
        user("cfr_old", "{\"cfr_grade\":\"a lot\",\"cfr_remote\":\"maybe\",\"cfr_hired\":\"soon\"}");
    }

    @Test
    @DisplayName("Each custom field is a registry field: its own label, its attribute, a filter, never a sort")
    void customFieldsAreRegistryFields() {
        var list = registry.resolve(LIST);
        QueryField region = list.field("cfCfrRegion").orElseThrow();
        assertThat(region.label()).isEqualTo("Регион");
        assertThat(region.attribute()).isEqualTo("cfr_region");
        assertThat(region.type()).isEqualTo(QueryFieldType.TEXT);
        assertThat(region.searchable()).isTrue();
        assertThat(list.field("cfCfrGrade").orElseThrow().type()).isEqualTo(QueryFieldType.NUMBER);
        assertThat(list.field("cfCfrShift").orElseThrow().enumValues()).containsExactly("day", "night");
        assertThat(list.fields()).filteredOn(field -> field.attribute() != null).noneMatch(QueryField::sortable);
        assertThat(LIST.field("cfCfrRegion"))
                .as("the declared list stays as the code wrote it")
                .isEmpty();
    }

    @Test
    @DisplayName("Custom fields filter by type; values of the wrong shape read as empty instead of failing")
    void filtersByType() {
        assertThat(logins("[{\"field\":\"cfCfrRegion\",\"op\":\"eq\",\"value\":\"Tashkent\"}]"))
                .containsExactly("cfr_anna");
        assertThat(logins("[{\"field\":\"cfCfrGrade\",\"op\":\"gt\",\"value\":10}]"))
                .containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrShift\",\"op\":\"in\",\"value\":[\"night\"]}]"))
                .containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrRemote\",\"op\":\"eq\",\"value\":true}]"))
                .containsExactly("cfr_anna");
        assertThat(logins("[{\"field\":\"cfCfrHired\",\"op\":\"gte\",\"value\":\"2025-01-01\"}]"))
                .containsExactly("cfr_bek");
        assertThat(logins("[{\"field\":\"cfCfrGrade\",\"op\":\"empty\"}]"))
                .contains("cfr_old", "cfr_viewer")
                .doesNotContain("cfr_anna", "cfr_bek");
    }

    @Test
    @DisplayName("Free search looks in text custom fields; sorting by a custom field is refused")
    void searchAndSort() {
        assertThat(logins(null, null, "samark")).containsExactly("cfr_bek");
        assertThatThrownBy(() -> logins(null, "cfCfrGrade", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::code)
                                .containsExactly(QueryCompiler.SORT_INVALID));
    }

    @Test
    @DisplayName("A field added by an administrator appears in the list at once, without a release")
    void newFieldAppearsAtOnce() {
        assertThat(registry.resolve(LIST).field("cfCfrDesk")).isEmpty();
        fields.createField("USER", "cfr_desk", "Стол", "string", false, null, null, 9);
        assertThat(registry.resolve(LIST).field("cfCfrDesk")).isPresent();
    }

    @Test
    @DisplayName("5.0: date-and-time and time-of-day fields are checked on save, filtered as moments and times")
    void momentsAndTimesOfDay() {
        fields.createField("USER", "cfr_due", "Срок", "datetime", false, null, null, 11);
        fields.createField("USER", "cfr_slot", "Слот", "time", false, null, null, 12);
        var list = registry.resolve(LIST);
        assertThat(list.field("cfCfrDue").orElseThrow().type()).isEqualTo(QueryFieldType.INSTANT);
        assertThat(list.field("cfCfrSlot").orElseThrow().type()).isEqualTo(QueryFieldType.TIME);

        assertThatThrownBy(
                        () -> fields.checkedAttributes("USER", Map.of("cfr_due", "2026-10-01T09:30", "cfr_slot", "9")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                                .containsExactly(
                                        org.assertj.core.groups.Tuple.tuple("attributes.cfr_due", "invalid_datetime"),
                                        org.assertj.core.groups.Tuple.tuple("attributes.cfr_slot", "invalid_time")));
        assertThat(fields.checkedAttributes("USER", Map.of("cfr_due", "2026-10-01T09:30:00Z", "cfr_slot", "08:15")))
                .containsEntry("cfr_slot", "08:15");

        user("cfr_early", "{\"cfr_due\":\"2026-10-01T09:30:00Z\",\"cfr_slot\":\"08:15\"}");
        user("cfr_late", "{\"cfr_due\":\"2026-10-03T12:00:00+05:00\",\"cfr_slot\":\"18:40:00\"}");
        user("cfr_shapeless", "{\"cfr_due\":\"tomorrow\",\"cfr_slot\":\"25:99\"}");
        assertThat(logins("[{\"field\":\"cfCfrDue\",\"op\":\"gte\",\"value\":\"2026-10-02T00:00:00Z\"}]"))
                .containsExactly("cfr_late");
        assertThat(logins("[{\"field\":\"cfCfrSlot\",\"op\":\"lt\",\"value\":\"12:00\"}]"))
                .containsExactly("cfr_early");
        assertThat(logins("[{\"field\":\"cfCfrSlot\",\"op\":\"eq\",\"value\":\"18:40\"}]"))
                .containsExactly("cfr_late");
        assertThat(logins("[{\"field\":\"cfCfrDue\",\"op\":\"empty\"}]"))
                .contains("cfr_shapeless")
                .doesNotContain("cfr_early", "cfr_late");
    }

    @Test
    @DisplayName("Changing a field's options and default is audited with the values before and after")
    void optionsChangeIsAudited() {
        var field = fields.createField("USER", "cfr_level", "Уровень", "select", false, null, List.of("a", "b"), 10);
        fields.updateField(field.id(), null, null, "b", List.of("a", "b", "c"), null, 1L);

        var row = jdbc.sql("""
                        select old_row::text as old_row, new_row::text as new_row from audit_log
                        where table_name = 'md_custom_fields' and row_pk = :id and event = 'U'
                        order by id desc limit 1
                        """)
                .param("id", String.valueOf(field.id()))
                .query((rs, n) -> List.of(rs.getString("old_row"), rs.getString("new_row")))
                .single();
        assertThat(row.get(0)).contains("\"options_json\"").doesNotContain("\\\"c\\\"");
        assertThat(row.get(1)).contains("\"default_value\": \"b\"").contains("\\\"c\\\"");
    }

    @Test
    @DisplayName("3.7: a number given to a string or select field is stored as text, so the equality filter finds it")
    void numberInTextFieldIsFound() {
        Map<String, Object> stored = fields.checkedAttributes("USER", Map.of("cfr_region", 4501, "cfr_grade", 3));
        assertThat(stored).containsEntry("cfr_region", "4501").containsEntry("cfr_grade", 3);
        user("cfr_num_new", mapper.writeValueAsString(stored));
        assertThat(logins("[{\"field\":\"cfCfrRegion\",\"op\":\"eq\",\"value\":\"4501\"}]"))
                .containsExactly("cfr_num_new");
    }

    @Test
    @DisplayName("3.7: the migration rewrites a number stored in a text field as text; other types keep theirs")
    void migrationRewritesStoredNumbers() {
        Long id = user("cfr_num_old", "{\"cfr_region\":4502,\"cfr_grade\":7,\"cfr_shift\":true}");
        String byRegion = "[{\"field\":\"cfCfrRegion\",\"op\":\"eq\",\"value\":\"4502\"}]";
        assertThat(logins(byRegion)).as("before the migration").isEmpty();

        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V145__custom_field_text_values_as_strings.sql"))
                .execute(ds);

        assertThat(logins(byRegion)).containsExactly("cfr_num_old");
        String attributes = jdbc.sql("select attributes::text from md_users where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(attributes)
                .contains("\"cfr_region\": \"4502\"")
                .contains("\"cfr_grade\": 7")
                .contains("\"cfr_shift\": \"true\"");
    }

    private static List<String> logins(String filter) {
        return logins(filter, "login", "cfr_");
    }

    /** The logins of a page of the user entity's list with its custom fields. */
    private static List<String> logins(@Nullable String filter, @Nullable String sort, @Nullable String search) {
        var plan = QueryCompiler.compile(registry.resolve(LIST), filter, sort, null, null, search);
        return new QueryListRepository(jdbc)
                .page(plan, EntityRowMapper.of(USERS, mapper)).items().stream()
                        .map(record -> String.valueOf(record.get("login")))
                        .toList();
    }

    private static Long user(String login, String attributes) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC', cast(:attributes as jsonb),
                                false, false)
                        returning id
                        """)
                .param("login", login)
                .param("attributes", attributes)
                .query(Long.class)
                .single();
    }
}
