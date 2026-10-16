package com.smartup24.cms.instance.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** Verifies the delivered golden declaration against actual Flyway tables and HTTP runtime (ADR-0037). */
class CatalogLocationsDeliveryTest extends EmbeddedPostgresTest {
    private static final String CODE = "catalog.locations";
    private static final String RECORDS = "/api/v1/entities/" + CODE;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void formOnlyFieldIsAbsentFromTheListButRetainsItsFormAndStoredValue() throws Exception {
        TestSession author = session("admin");
        var form = author.send(get("/api/v1/form-meta/" + CODE));
        var query = author.send(get("/api/v1/query-meta/" + CODE));
        assertThat(form.getStatus()).isEqualTo(200);
        assertThat(query.getStatus()).isEqualTo(200);
        assertThat(keys(form)).contains("details", "address", "capacity", "meeting");
        assertThat(keys(query)).contains("address", "capacity", "meeting").doesNotContain("details");
        var created = author.send(post(RECORDS), values());
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        long id = ((Number) TestSession.object(created).get("id")).longValue();
        var record = author.send(get(RECORDS + "/" + id));
        assertThat(record.getStatus()).isEqualTo(200);
        assertThat(TestSession.object(record)).containsEntry("details", "A retained form-only value");
        assertThat(jdbc.sql("select details from catalog_locations where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("A retained form-only value");
    }

    @Test
    void migrationGrantsOnlyAdministratorsAndManagerCannotReadTheNewForm() throws Exception {
        assertThat(jdbc.sql("""
                        select count(*) from md_role_permissions p join md_roles r on r.id = p.role_id
                        where p.form_code = :code and r.pcode in ('manager', 'user', 'auditor')
                        """).param("code", CODE).query(Long.class).single()).isZero();
        TestSession manager = session("manager");
        assertThat(manager.send(get(RECORDS)).getStatus()).isEqualTo(404);
        assertThat(manager.send(get("/api/v1/form-meta/" + CODE)).getStatus()).isEqualTo(404);
        assertThat(manager.send(get("/api/v1/query-meta/" + CODE)).getStatus()).isEqualTo(404);
    }

    @Test
    void requiredAndSystemFieldRulesRemainServerEnforced() throws Exception {
        TestSession author = session("admin");
        Map<String, Object> missing = values();
        missing.remove("address");
        assertThat(author.send(post(RECORDS), missing).getStatus()).isEqualTo(422);
        Map<String, Object> system = values();
        system.put("modifiedAt", "2026-10-01T00:00:00Z");
        assertThat(author.send(post(RECORDS), system).getStatus()).isEqualTo(422);
    }

    private TestSession session(String role) throws Exception {
        TestUsers users = TestUsers.of(context);
        var user = users.withRightsOf(role, users.unit("studio-delivery"));
        return TestSession.signIn(context, user.login());
    }

    private static List<String> keys(MockHttpServletResponse response) throws Exception {
        return ((List<?>) TestSession.object(response).get("fields"))
                .stream()
                        .map(field -> String.valueOf(((Map<?, ?>) field).get("key")))
                        .toList();
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "Golden location");
        values.put("code", "studio-" + UUID.randomUUID());
        values.put("address", "Example address");
        values.put("details", "A retained form-only value");
        values.put("capacity", 12.5);
        values.put("opened", "2026-10-01");
        values.put("meeting", "2026-10-01T09:30:00Z");
        values.put("closing", "17:30");
        values.put("active", true);
        values.put("email", "example@example.com");
        values.put("phone", "+998901234567");
        values.put("website", "https://example.com");
        return values;
    }
}
