package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.support.TestSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Declarations of the reference forms of stage 1 (ADR-0041) and the HTTP steps that activate them, fill their reference
 * lists and save their records, shared by the tests of the references and the data scope.
 */
final class StudioFormFixtures {

    /** Clients: a plain live form other forms refer to; every record is seen by whoever may view the form. */
    Map<String, Object> clients(String name) {
        return declaration(name, "Клиенты", "all", List.of(field("phone", "text", false, Map.of("maxLength", 30))));
    }

    /** The visit of a sales representative: a client, a date, a result chosen from fixed options, a comment. */
    Map<String, Object> visits(String name, String clients) {
        return declaration(
                name,
                "Визиты",
                "org_unit",
                List.of(
                        field("client", "ref", true, Map.of("target", Map.of("kind", "form", "code", clients))),
                        field("visitdate", "date", true, Map.of()),
                        field(
                                "result",
                                "select",
                                true,
                                Map.of(
                                        "options",
                                        List.of(option("done", "Выполнен"), option("later", "Перенесён")),
                                        "defaultValue",
                                        "done")),
                        field("comment", "textarea", false, Map.of("maxLength", 1000))));
    }

    /** A client request: a client, a request type from a reference list, a responsible user. */
    Map<String, Object> requests(String name, String clients, String list) {
        return declaration(
                name,
                "Заявки",
                "all",
                List.of(
                        field("client", "ref", false, Map.of("target", Map.of("kind", "form", "code", clients))),
                        field("kind", "ref", true, Map.of("target", Map.of("kind", "list", "code", list))),
                        field("responsible", "user", false, Map.of())));
    }

    /** Prepares and activates a declaration; the code of the new form. */
    String activate(TestSession publisher, Map<String, Object> declaration) throws Exception {
        MockHttpServletResponse activated = tryActivate(publisher, declaration);
        assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(201);
        return (String) TestSession.object(activated).get("entityCode");
    }

    MockHttpServletResponse tryActivate(TestSession publisher, Map<String, Object> declaration) throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        return publisher.send(post("/api/v1/studio/forms"), TestSession.object(prepared), null);
    }

    /** A record; its id. */
    long create(TestSession writer, String code, Map<String, ?> body) throws Exception {
        MockHttpServletResponse created = writer.send(post("/api/v1/entities/" + code), body);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) TestSession.object(created).get("id")).longValue();
    }

    /** A reference list whose items are named after their codes in each language, in the order given. */
    void list(TestSession manager, String code, List<String> items) throws Exception {
        MockHttpServletResponse created = manager.send(
                post("/api/v1/studio/lists"),
                Map.of("code", code, "name", Map.of("ru", "Типы", "uz", "Turlar", "en", "Kinds")));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        int order = 0;
        for (String item : items) {
            MockHttpServletResponse added = manager.send(
                    post("/api/v1/studio/lists/" + code + "/items"),
                    Map.of("code", item, "name", names(item), "sortOrder", ++order));
            assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
        }
    }

    /** Deactivates an item at its first revision. */
    void deactivate(TestSession manager, String list, String item) throws Exception {
        MockHttpServletResponse changed = manager.send(
                put("/api/v1/studio/lists/" + list + "/items/" + item).header("If-Match", "\"1\""),
                Map.of("name", names(item), "sortOrder", 99, "active", false));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
    }

    private static Map<String, String> names(String item) {
        return Map.of("ru", "Тип " + item, "uz", "Turi " + item, "en", "Kind " + item);
    }

    private static Map<String, Object> declaration(String name, String title, String scope, List<Object> fields) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("formatVersion", 3);
        declaration.put("kind", "simple-form");
        declaration.put("module", Map.of("code", "studio", "title", labels("Созданные формы")));
        declaration.put("entity", Map.of("name", name, "title", labels(title), "scope", scope));
        declaration.put("fields", new ArrayList<>(fields));
        return declaration;
    }

    private static Map<String, Object> field(String id, String type, boolean required, Map<String, Object> extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", id);
        field.put("type", type);
        field.put("labels", labels("Поле " + id));
        field.put("required", required);
        field.put("listed", true);
        field.putAll(extra);
        return field;
    }

    private static Map<String, Object> option(String code, String ru) {
        return Map.of("code", code, "labels", Map.of("ru", ru, "uz", code, "en", code));
    }

    private static Map<String, String> labels(String text) {
        return Map.of("ru", text, "uz", text, "en", text);
    }
}
