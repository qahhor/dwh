package com.smartup24.cms.instance.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * The reference document beyond the entity contract (ADR-0032, 9; plan 10/10, item 5.7): the lines take the order's
 * currency and sum into its total, a line in another currency is refused on the line, posting needs a line, the audit
 * names what a save changed in the lines, a bulk action takes a transition, a cancelled order is only read, and the
 * module switched off hides the entity.
 */
class ExampleOrderDocumentIntegrationTest extends EmbeddedPostgresTest {

    private static final String ORDERS = "/api/v1/entities/" + ExampleOrderEntity.CODE;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession session;
    private String tag;

    @BeforeEach
    void signIn() throws Exception {
        modules.toggleModuleStatus("example", true);
        TestUsers users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        TestUser clerk = users.withRights(
                Map.of(ExampleOrderEntity.CODE, Set.of("view", "create", "update", "post", "unpost", "cancel")),
                users.unit("orders-" + tag));
        session = TestSession.signIn(wac, clerk.login());
    }

    @Test
    @DisplayName("5.7: lines take the order's currency and sum into its total; a line in another currency is refused")
    void linesFollowTheOrdersCurrency() throws Exception {
        Map<String, Object> order =
                created(List.of(line("Flour", "3", "10.00"), line("Sugar", "1.5", "4.20"), line("Salt", "2", "0.55")));
        assertThat(order.get("number").toString()).matches("ORD-\\d{6}");
        assertThat(order).containsEntry("status", "draft").containsEntry("total", money("37.40", "UZS"));
        List<Map<String, Object>> lines = lines(order);
        assertThat(lines).extracting(row -> row.get("position")).containsExactly(1, 2, 3);
        assertThat(lines.get(1))
                .containsEntry("price", money("4.20", "UZS"))
                .containsEntry("amount", money("6.30", "UZS"));

        MockHttpServletResponse usd = session.send(
                patch(ORDERS + "/" + order.get("id")).header("If-Match", "\"1\""), Map.of("currency", "USD"));
        assertThat(usd.getStatus()).as(usd.getContentAsString()).isEqualTo(200);
        assertThat(lines(TestSession.object(usd)).getFirst()).containsEntry("price", money("10.00", "USD"));

        MockHttpServletResponse euro = session.send(
                post(ORDERS),
                Map.of(
                        "customer",
                        "Euro " + tag,
                        "lines",
                        List.of(Map.of("product", "Tea", "qty", "1", "price", money("2.00", "EUR")))));
        assertThat(euro.getStatus()).as(euro.getContentAsString()).isEqualTo(422);
        assertThat(euro.getContentAsString())
                .contains("\"field\":\"lines[0].price\"", "error.field.currency_not_allowed");
    }

    @Test
    @DisplayName("5.7: posting needs a line; the history names the lines added and the transition")
    void postingNeedsALineAndReachesTheHistory() throws Exception {
        Map<String, Object> empty = created(List.of());
        MockHttpServletResponse refused = take(empty, "post", 1);
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("\"field\":\"lines\"", "\"code\":\"required\"");

        Map<String, Object> order = created(List.of(line("Flour", "3", "10.00")));
        MockHttpServletResponse posted = take(order, "post", 1);
        assertThat(posted.getStatus()).as(posted.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(posted))
                .containsEntry("status", "posted")
                .containsEntry("actions", List.of("create", "update", "unpost"));

        String history = session.send(get("/api/v1/history/" + ExampleOrderEntity.CODE + "/" + order.get("id")))
                .getContentAsString();
        assertThat(history)
                .contains("\"field\":\"lines\"", "\"labelKey\":\"example.orders.lines\"", "\"added\"")
                .contains("\"field\":\"status\"", "\"newValue\":\"posted\"", "\"post\"");
    }

    @Test
    @DisplayName("5.7: the audit of a change of the lines names the changed fields, the removed rows and the count")
    void theAuditNamesTheChangeOfTheLines() throws Exception {
        Map<String, Object> order = created(List.of(line("Flour", "3", "10.00"), line("Sugar", "1", "4.00")));
        List<Map<String, Object>> lines = lines(order);
        MockHttpServletResponse changed = session.send(
                patch(ORDERS + "/" + order.get("id")).header("If-Match", "\"1\""),
                Map.of(
                        "lines",
                        List.of(Map.of("id", lines.getFirst().get("id"), "qty", "5"), line("Tea", "1", "1.00"))));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        String newRow = jdbc.sql("""
                        select new_row::text from audit_log
                        where table_name = 'ex_orders' and row_pk = :id and event = 'U'
                        order by id desc limit 1
                        """)
                .param("id", String.valueOf(order.get("id")))
                .query(String.class)
                .single();
        Map<String, Object> row = TestSession.JSON.readValue(newRow, Map.class);
        Map<?, ?> summary = (Map<?, ?>) row.get("lines");
        assertThat(summary.get("count")).isEqualTo(2);
        assertThat((List<?>) summary.get("changed"))
                .singleElement()
                .isEqualTo(Map.of("id", lines.getFirst().get("id"), "qty", 5.0));
        assertThat(summary.get("removed")).isEqualTo(List.of(lines.get(1).get("id")));
        assertThat((List<?>) summary.get("added")).hasSize(1);
    }

    @Test
    @DisplayName("5.7: a bulk action takes a transition record by record, each from its own state")
    void aBulkActionTakesATransition() throws Exception {
        Map<String, Object> first = created(List.of(line("Flour", "3", "10.00")));
        Map<String, Object> second = created(List.of(line("Sugar", "1", "4.00")));
        assertThat(take(second, "post", 1).getStatus()).isEqualTo(200);
        MockHttpServletResponse bulk = session.send(
                post(ORDERS + "/bulk"), Map.of("action", "post", "ids", List.of(first.get("id"), second.get("id"))));
        assertThat(bulk.getStatus()).as(bulk.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(bulk)).containsEntry("succeeded", 1).containsEntry("failed", 1);
        assertThat(bulk.getContentAsString()).contains("entity_transition_not_allowed");
    }

    @Test
    @DisplayName("5.7: a cancelled order is only read: no change is offered and a changed field is read-only")
    void aCancelledOrderIsOnlyRead() throws Exception {
        Map<String, Object> order = created(List.of(line("Flour", "3", "10.00")));
        MockHttpServletResponse cancelled = take(order, "cancel", 1);
        assertThat(cancelled.getStatus()).as(cancelled.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(cancelled)).containsEntry("actions", List.of("create"));
        MockHttpServletResponse refused = session.send(
                patch(ORDERS + "/" + order.get("id")).header("If-Match", "\"2\""), Map.of("comment", "late"));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("\"field\":\"comment\"", "\"code\":\"readonly\"");
    }

    @Test
    @DisplayName("5.7: the module switched off hides the entity: 404, as an unknown one")
    void theModuleSwitchedOffHidesTheEntity() throws Exception {
        modules.toggleModuleStatus("example", false);
        MockHttpServletResponse list = session.send(get(ORDERS));
        assertThat(list.getStatus()).isEqualTo(404);
        assertThat(list.getContentAsString()).contains("error.common.entity_not_found");
    }

    private Map<String, Object> created(List<Map<String, Object>> lines) throws Exception {
        MockHttpServletResponse response =
                session.send(post(ORDERS), Map.of("customer", "Customer " + tag, "lines", lines));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private MockHttpServletResponse take(Map<String, Object> order, String transition, long revision) throws Exception {
        return session.send(post(ORDERS + "/" + order.get("id") + "/actions/" + transition)
                .header("If-Match", "\"" + revision + "\""));
    }

    private static Map<String, Object> line(String product, String qty, String price) {
        return Map.of("product", product, "qty", qty, "price", price);
    }

    private static Map<String, Object> money(String amount, String currency) {
        return Map.of("amount", amount, "currency", currency);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> order) {
        return (List<Map<String, Object>>) order.get("lines");
    }
}
