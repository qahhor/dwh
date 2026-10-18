package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

/**
 * Format 5 of a live form (ADR-0042): the reference order — a total computed from quantity, price and discount, today's
 * date by default, a discount reason shown and required only above 10 %, a rule that keeps the discount at 30 % or less
 * — enforced by the server on every write path whatever the client sends, and given to the web in form-meta.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioFormulaLogicHttpTest extends EmbeddedPostgresTest {
    private static final String ZONE = "Asia/Tashkent";

    @Autowired
    private WebApplicationContext context;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long unit;
    private TestSession publisher;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        unit = users.unit("formula-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.forms", Set.of("publish"), "md.settings", Set.of("update")), unit)
                        .login());
    }

    @Test
    void theServerComputesDefaultsHidesRequiresAndChecksTheRulesWhateverTheClientSends() throws Exception {
        String code = forms.activate(publisher, order("orders_" + tag));
        TestSession writer = writer(code);

        Map<String, Object> created = create(writer, code, body(3, "19.99", "15", "VIP", Map.of("total", "999")));
        assertThat(created.get("total")).isIn(50.97, "50.97");
        assertThat(created.get("orderdate"))
                .isEqualTo(LocalDate.now(ZoneId.of(ZONE)).toString());
        assertThat(created.get("reason")).isEqualTo("VIP");

        Map<String, Object> small = create(writer, code, body(2, "10", "5", "not shown", Map.of()));
        assertThat(small.get("reason")).isNull();
        assertThat(small.get("total")).isIn(19.0, "19.00", 19);

        MockHttpServletResponse missing =
                writer.send(post("/api/v1/entities/" + code), body(1, "10", "15", null, Map.of()));
        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(error(missing, "reason")).containsEntry("code", "required");

        MockHttpServletResponse ruled =
                writer.send(post("/api/v1/entities/" + code), body(1, "10", "40", "x", Map.of()));
        assertThat(ruled.getStatus()).isEqualTo(422);
        Map<String, Object> rule = error(ruled, "discount");
        assertThat(rule).containsEntry("code", "rule_failed").containsEntry("messageKey", "error.studio.rule_failed");
        assertThat(rule.get("message")).isEqualTo("Скидка не больше 30%");

        long id = ((Number) created.get("id")).longValue();
        MockHttpServletResponse changed = writer.send(
                patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"1\""),
                Map.of("price", "20", "total", "1"));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(changed).get("total")).isIn(51.0, "51.00", 51);

        Map<String, Object> bulk = TestSession.object(writer.send(
                post("/api/v1/entities/" + code + "/bulk"),
                Map.of("action", "update", "ids", List.of(id), "params", Map.of("discount", "35"))));
        assertThat(bulk.get("failed")).isEqualTo(1);
        Map<String, Object> kept = TestSession.object(writer.send(get("/api/v1/entities/" + code + "/" + id)));
        assertThat(kept.get("discount")).isIn(15.0, "15.00", 15);

        Map<String, Object> list = TestSession.object(writer.send(get("/api/v1/entities/" + code + "?limit=10")));
        assertThat(String.valueOf(list.get("items"))).contains("total=51");
    }

    @Test
    void formMetaGivesTheCheckedTreesAndTheRules() throws Exception {
        String code = forms.activate(publisher, order("ordermeta_" + tag));
        Map<String, Object> meta = TestSession.object(writer(code).send(get("/api/v1/form-meta/" + code)));
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Object field : (List<?>) meta.get("fields")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) field;
            fields.put((String) item.get("key"), item);
        }
        assertThat(fields.get("total"))
                .containsEntry("computed", true)
                .containsEntry("readonly", true)
                .containsKey("formula");
        assertThat(fields.get("orderdate")).containsKey("defaultFormula");
        assertThat(fields.get("reason")).containsKeys("visibleIf", "requiredIf");
        assertThat(fields.get("qty")).doesNotContainKeys("formula", "visibleIf");
        assertThat(String.valueOf(meta.get("rules")))
                .contains("maxdiscount")
                .contains("studio.live.ordermeta_" + tag + ".rule.maxdiscount");
    }

    @Test
    void aFormulaThatCannotGiveAValueIsAProblemOfItsField() throws Exception {
        Map<String, Object> declaration = order("orderzero_" + tag);
        fields(declaration).add(field("unit", "number", false, Map.of("formula", "total / qty", "scale", 2)));
        String code = forms.activate(publisher, declaration);
        TestSession writer = writer(code);
        MockHttpServletResponse zero =
                writer.send(post("/api/v1/entities/" + code), body(0, "10", "0", null, Map.of()));
        assertThat(zero.getStatus()).isEqualTo(422);
        assertThat(error(zero, "unit")).containsEntry("code", "formula_division_by_zero");
    }

    @Test
    void theActivationRefusesAWrongFormulaWithItsReasonAndPosition() throws Exception {
        Map<String, Object> declaration = order("orderbad_" + tag);
        fields(declaration).add(field("bad", "number", false, Map.of("formula", "qty * ", "scale", 0)));
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).isEqualTo(422);
        Map<String, Object> problem = error(prepared, "fields[6].formula");
        assertThat(problem).containsEntry("code", "formula_syntax");
        assertThat(String.valueOf(problem.get("params"))).contains("position=7");

        Map<String, Object> cyclic = order("ordercycle_" + tag);
        fields(cyclic).add(field("aa", "number", false, Map.of("formula", "bb + 1", "scale", 0)));
        fields(cyclic).add(field("bb", "number", false, Map.of("formula", "aa + 1", "scale", 0)));
        MockHttpServletResponse cycle = publisher.send(post("/api/v1/form-studio/new-forms/prepare"), cyclic, null);
        assertThat(cycle.getStatus()).isEqualTo(422);
        assertThat(cycle.getContentAsString()).contains("formula_cycle");
    }

    @Test
    void theEditorChecksOneFormulaAtATime() throws Exception {
        List<Map<String, Object>> fields =
                List.of(Map.of("id", "qty", "type", "number"), Map.of("id", "reason", "type", "text"));
        Map<String, Object> valid = check(fields, "qty * 2", "formula", "qty");
        assertThat(valid).containsEntry("valid", true).containsEntry("type", "number");
        Map<String, Object> wrong = check(fields, "qty + reason", "rule", null);
        assertThat(wrong)
                .containsEntry("valid", false)
                .containsEntry("reason", "type")
                .containsEntry("position", 5)
                .containsEntry("messageKey", "error.formula.type");
        assertThat(check(fields, "isEmpty(reason)", "visibleIf", "reason")).containsEntry("reason", "self");
        assertThat(check(fields, "qty > 1", "requiredIf", "reason")).containsEntry("valid", true);
        assertThat(check(fields, "\"x\"", "formula", "qty")).containsEntry("reason", "result");
        assertThat(check(fields, "qty + 1", "formula", null)).containsEntry("reason", "result");
        assertThat(check(fields, "qty + 1", "formula", "nosuch")).containsEntry("reason", "result");
        List<Map<String, Object>> typed = List.of(
                Map.of("id", "qty", "type", "number"),
                Map.of("id", "kind", "type", "ref", "target", "list"),
                Map.of("id", "client", "type", "ref", "target", "form"));
        assertThat(check(typed, "if(kind = \"vip\", 10, 0)", "defaultFormula", "qty"))
                .containsEntry("valid", true)
                .containsEntry("type", "number");
        assertThat(check(typed, "client = 7", "readonlyIf", "qty")).containsEntry("type", "bool");
    }

    @Test
    void aNewVersionChangesFormulasFreelyButNeverComputesAnEnteredField() throws Exception {
        String code = forms.activate(publisher, order("orderver_" + tag));
        Map<String, Object> computedPrice = order("orderver_" + tag);
        replace(computedPrice, "discount", Map.of("formula", "qty * 2"));
        MockHttpServletResponse refused = version(code, computedPrice);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("change_computed");

        Map<String, Object> retotal = order("orderver_" + tag);
        replace(retotal, "total", Map.of("formula", "round(qty * price, 2)"));
        MockHttpServletResponse accepted = version(code, retotal);
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
        TestSession writer = writer(code);
        assertThat(create(writer, code, body(3, "10", "20", "VIP", Map.of())).get("total"))
                .isIn(30.0, "30.00", 30);
    }

    private Map<String, Object> check(List<Map<String, Object>> fields, String formula, String kind, String field)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        body.put("formula", formula);
        body.put("kind", kind);
        if (field != null) body.put("field", field);
        MockHttpServletResponse response = publisher.send(post("/api/v1/studio/formulas/check"), body, null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return TestSession.object(response);
    }

    private MockHttpServletResponse version(String code, Map<String, Object> declaration) throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        return publisher.send(
                post("/api/v1/studio/forms/" + code + "/versions").header("If-Match", "\"1\""),
                TestSession.object(prepared),
                null);
    }

    private TestSession writer(String code) throws Exception {
        return TestSession.signIn(
                context,
                users.withRights(Map.of(code, Set.of("view", "create", "update")), unit)
                        .login());
    }

    private static Map<String, Object> create(TestSession writer, String code, Map<String, Object> body)
            throws Exception {
        MockHttpServletResponse created =
                writer.send(post("/api/v1/entities/" + code).header("X-Time-Zone", ZONE), body);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return TestSession.object(created);
    }

    private static Map<String, Object> body(
            int qty, String price, String discount, String reason, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qty", qty);
        body.put("price", price);
        body.put("discount", discount);
        if (reason != null) body.put("reason", reason);
        body.putAll(extra);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(MockHttpServletResponse response, String field) throws Exception {
        for (Object error : (List<Object>) TestSession.object(response).get("errors")) {
            Map<String, Object> item = (Map<String, Object>) error;
            if (field.equals(item.get("field"))) return item;
        }
        throw new AssertionError("No problem of " + field + " in " + response.getContentAsString());
    }

    /** The reference order of ADR-0042 in format 5. */
    private Map<String, Object> order(String name) {
        Map<String, Object> declaration = forms.plain(
                name,
                "Заказ",
                "all",
                List.of(
                        field("qty", "number", true, Map.of("scale", 0)),
                        field("price", "number", true, Map.of("scale", 2)),
                        field("discount", "number", false, Map.of("scale", 2)),
                        field(
                                "total",
                                "number",
                                false,
                                Map.of(
                                        "scale",
                                        2,
                                        "formula",
                                        "round(qty * price * (1 - coalesce(discount, 0) / 100), 2)")),
                        field("orderdate", "date", true, Map.of("defaultFormula", "today()")),
                        field(
                                "reason",
                                "text",
                                false,
                                Map.of(
                                        "maxLength",
                                        200,
                                        "visibleIf",
                                        "discount > 10",
                                        "requiredIf",
                                        "discount > 10"))));
        declaration.put("formatVersion", 5);
        declaration.put(
                "rules",
                List.of(Map.of(
                        "key",
                        "maxdiscount",
                        "formula",
                        "coalesce(discount, 0) <= 30",
                        "field",
                        "discount",
                        "message",
                        Map.of(
                                "ru",
                                "Скидка не больше 30%",
                                "uz",
                                "Chegirma 30% dan oshmaydi",
                                "en",
                                "The discount is at most 30%"))));
        return declaration;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> fields(Map<String, Object> declaration) {
        return (List<Object>) declaration.get("fields");
    }

    @SuppressWarnings("unchecked")
    private static void replace(Map<String, Object> declaration, String id, Map<String, Object> extra) {
        List<Object> replaced = new ArrayList<>();
        for (Object field : fields(declaration)) {
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) field);
            if (id.equals(item.get("id"))) item.putAll(extra);
            replaced.add(item);
        }
        declaration.put("fields", replaced);
    }
}
