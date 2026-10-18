package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.labels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

/**
 * Format 6 of a live form (ADR-0043): the reference order with lines and approval — a client, a yearly number
 * of six digits after a prefix and the year, lines of a product from a reference list, a quantity, a price and a computed sum, a
 * header total of the line sums, and the statuses draft, pending, approved and rejected. The server computes the lines
 * and the total whatever the client sends, numbers each order in turn, lets each transition only to holders of its
 * right — never the author's own approval — asks a comment where the form does, tells the approvers, keeps the
 * transitions with their comments in the history and refuses every change of an approved order.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioDocumentHttpTest extends EmbeddedPostgresTest {
    private static final String ZONE = "Asia/Tashkent";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long unit;
    private TestSession publisher;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        unit = users.unit("orders-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "md.settings", Set.of("update"),
                                        "studio.lists", Set.of("view", "manage")),
                                unit)
                        .login());
    }

    @Test
    void anOrderWithLinesIsNumberedComputedApprovedOnlyByAnotherPersonAndThenOnlyRead() throws Exception {
        String products = "products_" + tag;
        forms.list(publisher, products, List.of("tea", "coffee"));
        String clients = forms.activate(publisher, forms.clients("clients_" + tag));
        String code = forms.activate(publisher, order("orders_" + tag, clients, products));
        TestUsers.TestUser authorUser = users.withRights(
                Map.of(code, Set.of("view", "create", "update", "submit"), clients, Set.of("view", "create")), unit);
        TestSession author = TestSession.signIn(context, authorUser.login());
        TestUsers.TestUser managerUser = users.withRights(Map.of(code, Set.of("view", "approve", "reject")), unit);
        TestSession manager = TestSession.signIn(context, managerUser.login());
        long client = forms.create(author, clients, Map.of("name", "Клиент", "code", "c1"));

        Map<String, Object> first = create(author, code, client, List.of(line("tea", "2", "10.50", "999")));
        String year = String.valueOf(LocalDate.now(ZoneId.of(ZONE)).getYear());
        assertThat(first.get("docNumber")).isEqualTo("ЗК-" + year + "-000001");
        assertThat(first.get("docStatus")).isEqualTo("draft");
        assertThat(String.valueOf(first.get("total"))).isIn("21", "21.0", "21.00");
        assertThat(String.valueOf(rows(first).getFirst().get("amount"))).isIn("21", "21.0", "21.00");
        assertThat(actions(first)).contains("submit").doesNotContain("approve");

        Map<String, Object> empty = create(author, code, client, List.of());
        assertThat(empty.get("docNumber")).isEqualTo("ЗК-" + year + "-000002");
        assertThat(actions(empty)).doesNotContain("submit");
        MockHttpServletResponse unmet = transition(author, code, id(empty), "submit", 1, Map.of());
        assertThat(unmet.getStatus()).isEqualTo(422);
        assertThat(unmet.getContentAsString()).contains("transition_condition");

        long order = id(first);
        assertThat(transition(manager, code, order, "submit", 1, Map.of()).getStatus())
                .isEqualTo(403);
        MockHttpServletResponse submitted = transition(author, code, order, "submit", 1, Map.of());
        assertThat(submitted.getStatus()).as(submitted.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(submitted).get("docStatus")).isEqualTo("pending");
        assertThat(notices(managerUser.id(), code, order)).isEqualTo(1);
        assertThat(notices(authorUser.id(), code, order)).isZero();

        MockHttpServletResponse locked = author.send(
                patch("/api/v1/entities/" + code + "/" + order).header("If-Match", "\"2\""),
                Map.of("items", List.of(line("coffee", "1", "5", null))));
        assertThat(locked.getStatus()).isEqualTo(422);
        assertThat(locked.getContentAsString()).contains("readonly");

        MockHttpServletResponse silent = transition(manager, code, order, "reject", 2, Map.of());
        assertThat(silent.getStatus()).isEqualTo(422);
        assertThat(silent.getContentAsString()).contains("error.studio.comment_required");
        MockHttpServletResponse approved =
                transition(manager, code, order, "approve", 2, Map.of("comment", "Цены согласованы"));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        Map<String, Object> done = TestSession.object(approved);
        assertThat(done.get("docStatus")).isEqualTo("approved");
        assertThat(done.get("docComment")).isEqualTo("Цены согласованы");
        assertThat(actions(done)).isEmpty();

        MockHttpServletResponse changed = manager.send(
                patch("/api/v1/entities/" + code + "/" + order).header("If-Match", "\"3\""), Map.of("client", client));
        assertThat(changed.getStatus()).isIn(403, 422);
        MockHttpServletResponse edited = author.send(
                patch("/api/v1/entities/" + code + "/" + order).header("If-Match", "\"3\""),
                Map.of("items", List.of(line("coffee", "1", "5", null))));
        assertThat(edited.getStatus()).isEqualTo(422);

        String history =
                author.send(get("/api/v1/history/" + code + "/" + order)).getContentAsString();
        assertThat(history).contains("approve").contains("Цены согласованы").contains("submit");

        String filtered = author.send(get("/api/v1/entities/" + code)
                        .param("filter", "[{\"field\":\"docStatus\",\"op\":\"eq\",\"value\":\"approved\"}]"))
                .getContentAsString();
        assertThat(filtered).contains("ЗК-" + year + "-000001").doesNotContain("ЗК-" + year + "-000002");
        String searched = author.send(get("/api/v1/entities/" + code).param("q", "000002"))
                .getContentAsString();
        assertThat(searched).contains("ЗК-" + year + "-000002").doesNotContain("ЗК-" + year + "-000001");
    }

    @Test
    void theAuthorNeverApprovesTheirOwnOrderAndAnOutsiderTakesNoTransition() throws Exception {
        String products = "goods_" + tag;
        forms.list(publisher, products, List.of("tea"));
        String clients = forms.activate(publisher, forms.clients("buyers_" + tag));
        String code = forms.activate(publisher, order("deals_" + tag, clients, products));
        TestSession boss = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        code,
                                        Set.of("view", "create", "update", "submit", "approve", "reject"),
                                        clients,
                                        Set.of("view", "create")),
                                unit)
                        .login());
        long client = forms.create(boss, clients, Map.of("name", "Клиент", "code", "c2"));
        long order = id(create(boss, code, client, List.of(line("tea", "1", "3", null))));
        assertThat(transition(boss, code, order, "submit", 1, Map.of()).getStatus())
                .isEqualTo(200);
        Map<String, Object> pending = TestSession.object(boss.send(get("/api/v1/entities/" + code + "/" + order)));
        assertThat(actions(pending)).doesNotContain("approve", "reject");
        MockHttpServletResponse own = transition(boss, code, order, "approve", 2, Map.of());
        assertThat(own.getStatus()).isEqualTo(422);
        assertThat(own.getContentAsString()).contains("transition_author");

        TestSession outsider = TestSession.signIn(
                context, users.withRights(Map.of(code, Set.of("view")), unit).login());
        assertThat(transition(outsider, code, order, "approve", 2, Map.of()).getStatus())
                .isEqualTo(403);
        MockHttpServletResponse bad = boss.send(
                post("/api/v1/entities/" + code),
                Map.of("client", client, "items", List.of(line("nosuch", "1", "1", null))));
        assertThat(bad.getStatus()).isEqualTo(422);
        assertThat(bad.getContentAsString()).contains("items[0].product");
    }

    @Test
    void formMetaGivesTheLinesFormulasStatusesAndTransitions() throws Exception {
        String products = "items_" + tag;
        forms.list(publisher, products, List.of("tea"));
        String clients = forms.activate(publisher, forms.clients("people_" + tag));
        String code = forms.activate(publisher, order("bills_" + tag, clients, products));
        TestSession viewer = TestSession.signIn(
                context,
                users.withRights(Map.of(code, Set.of("view", "create")), unit).login());
        String meta = viewer.send(get("/api/v1/form-meta/" + code)).getContentAsString();
        assertThat(meta)
                .contains("\"collections\"")
                .contains("\"key\":\"items\"")
                .contains("\"color\":\"blue\"")
                .contains("\"comment\":\"required\"")
                .contains("studio.live.bills_" + tag + ".transition.approve")
                .contains("lines.items.amount")
                .contains("doc.discount");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> record) {
        return (List<Map<String, Object>>) record.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<String> actions(Map<String, Object> record) {
        return (List<String>) record.get("actions");
    }

    private static long id(Map<String, Object> record) {
        return ((Number) record.get("id")).longValue();
    }

    private int notices(long user, String code, long order) {
        Integer count = jdbc.sql(
                        "select count(*) from ms_notifications where user_id = :user and source_code like :code")
                .param("user", user)
                .param("code", "studio-step-" + code + "-" + order + "-%")
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private static MockHttpServletResponse transition(
            TestSession session, String code, long id, String action, long revision, Map<String, Object> body)
            throws Exception {
        return session.send(
                post("/api/v1/entities/" + code + "/" + id + "/actions/" + action)
                        .header("If-Match", "\"" + revision + "\""),
                body);
    }

    private static Map<String, Object> create(
            TestSession session, String code, long client, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("discount", "0");
        body.put("items", lines);
        MockHttpServletResponse created =
                session.send(post("/api/v1/entities/" + code).header("X-Time-Zone", ZONE), body);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return TestSession.object(created);
    }

    private static Map<String, Object> line(String product, String qty, String price, String amount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product", product);
        line.put("qty", qty);
        line.put("price", price);
        if (amount != null) line.put("amount", amount);
        return line;
    }

    /** The reference order of ADR-0043 in format 6. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> order(String name, String clients, String products) {
        Map<String, Object> declaration = forms.plain(
                name,
                "Заказ",
                "all",
                List.of(
                        field("client", "ref", true, Map.of("target", Map.of("kind", "form", "code", clients))),
                        field("discount", "number", false, Map.of("scale", 2)),
                        field("total", "number", false, Map.of("scale", 2, "formula", "sum(lines.items.amount)"))));
        declaration.put("formatVersion", 6);
        Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) declaration.get("entity"));
        entity.put("numbering", Map.of("pattern", "ЗК-{YYYY}-{NNNNNN}", "reset", "year"));
        declaration.put("entity", entity);
        declaration.put(
                "lines",
                List.of(Map.of(
                        "key",
                        "items",
                        "labels",
                        labels("Строки заказа"),
                        "maxRows",
                        50,
                        "fields",
                        List.of(
                                column(
                                        "product",
                                        "ref",
                                        true,
                                        Map.of("target", Map.of("kind", "list", "code", products))),
                                column("qty", "number", true, Map.of("scale", 3)),
                                column("price", "number", true, Map.of("scale", 2)),
                                column(
                                        "amount",
                                        "number",
                                        false,
                                        Map.of(
                                                "scale",
                                                2,
                                                "formula",
                                                "round(qty * price * (1 - coalesce(doc.discount, 0) / 100), 2)"))))));
        declaration.put("workflow", workflow());
        return declaration;
    }

    private static Map<String, Object> workflow() {
        List<Object> states = new ArrayList<>();
        states.add(state("draft", "Черновик", "gray", Map.of()));
        states.add(state("pending", "На согласовании", "blue", Map.of("lockAll", true)));
        states.add(state("approved", "Согласовано", "green", Map.of("terminal", true)));
        states.add(state("rejected", "Отклонено", "red", Map.of("terminal", true)));
        List<Object> transitions = new ArrayList<>();
        transitions.add(transition("submit", "На согласование", "draft", "pending", Map.of("condition", "total > 0")));
        transitions.add(
                transition("approve", "Согласовать", "pending", "approved", Map.of("notAuthor", true, "notify", true)));
        transitions.add(transition(
                "reject",
                "Отклонить",
                "pending",
                "rejected",
                Map.of("notAuthor", true, "notify", true, "comment", "required")));
        return Map.of("states", states, "transitions", transitions);
    }

    private static Map<String, Object> state(String code, String ru, String color, Map<String, Object> extra) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("code", code);
        state.put("labels", labels(ru));
        state.put("color", color);
        state.putAll(extra);
        return state;
    }

    private static Map<String, Object> transition(
            String code, String ru, String from, String to, Map<String, Object> extra) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("code", code);
        transition.put("labels", labels(ru));
        transition.put("from", List.of(from));
        transition.put("to", to);
        transition.put("comment", "optional");
        transition.putAll(extra);
        return transition;
    }

    private static Map<String, Object> column(String id, String type, boolean required, Map<String, Object> extra) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("id", id);
        column.put("type", type);
        column.put("labels", labels("Колонка " + id));
        column.put("required", required);
        column.putAll(extra);
        return column;
    }
}
