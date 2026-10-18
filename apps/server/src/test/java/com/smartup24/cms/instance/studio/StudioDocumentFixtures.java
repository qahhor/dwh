package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.labels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.TestSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;

/** The reference order of ADR-0043 in format 6 and the HTTP steps the tests of documents share. */
final class StudioDocumentFixtures {
    static final String ZONE = "Asia/Tashkent";

    private StudioDocumentFixtures() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> record) {
        return (List<Map<String, Object>>) record.get("items");
    }

    @SuppressWarnings("unchecked")
    static List<String> actions(Map<String, Object> record) {
        return (List<String>) record.get("actions");
    }

    static long id(Map<String, Object> record) {
        return ((Number) record.get("id")).longValue();
    }

    static int notices(JdbcClient jdbc, long user, String code, long order) {
        Integer count = jdbc.sql(
                        "select count(*) from ms_notifications where user_id = :user and source_code like :code")
                .param("user", user)
                .param("code", "studio-step-" + code + "-" + order + "-%")
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    static MockHttpServletResponse transition(
            TestSession session, String code, long id, String action, long revision, Map<String, Object> body)
            throws Exception {
        return session.send(
                post("/api/v1/entities/" + code + "/" + id + "/actions/" + action)
                        .header("If-Match", "\"" + revision + "\""),
                body);
    }

    static Map<String, Object> create(TestSession session, String code, long client, List<Map<String, Object>> lines)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("discount", "0");
        body.put("items", lines);
        MockHttpServletResponse created =
                session.send(post("/api/v1/entities/" + code).header("X-Time-Zone", ZONE), body);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return TestSession.object(created);
    }

    static Map<String, Object> line(String product, String qty, String price, String amount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product", product);
        line.put("qty", qty);
        line.put("price", price);
        if (amount != null) line.put("amount", amount);
        return line;
    }

    /** The reference order of ADR-0043 in format 6. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> order(String name, String clients, String products) {
        Map<String, Object> declaration = new StudioFormFixtures()
                .plain(
                        name,
                        "Заказ",
                        "all",
                        List.of(
                                field("client", "ref", true, Map.of("target", Map.of("kind", "form", "code", clients))),
                                field("discount", "number", false, Map.of("scale", 2)),
                                field(
                                        "total",
                                        "number",
                                        false,
                                        Map.of("scale", 2, "formula", "sum(lines.items.amount)"))));
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

    static Map<String, Object> workflow() {
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

    static Map<String, Object> state(String code, String ru, String color, Map<String, Object> extra) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("code", code);
        state.put("labels", labels(ru));
        state.put("color", color);
        state.putAll(extra);
        return state;
    }

    static Map<String, Object> transition(String code, String ru, String from, String to, Map<String, Object> extra) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("code", code);
        transition.put("labels", labels(ru));
        transition.put("from", List.of(from));
        transition.put("to", to);
        transition.put("comment", "optional");
        transition.putAll(extra);
        return transition;
    }

    static Map<String, Object> column(String id, String type, boolean required, Map<String, Object> extra) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("id", id);
        column.put("type", type);
        column.put("labels", labels("Колонка " + id));
        column.put("required", required);
        column.putAll(extra);
        return column;
    }
}
