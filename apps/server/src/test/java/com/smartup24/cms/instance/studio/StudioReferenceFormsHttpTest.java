package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
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
 * The reference forms of stage 1 (ADR-0041, owner decision 4) over HTTP: a visit of a sales representative (a client —
 * a record of another live form, a date, a result — a fixed choice, a comment; seen by org unit) and a client request
 * (a client, a request type from a reference list, a responsible user). Values are checked against their targets on
 * write; reads name them; a viewer of another org unit sees no visit at all.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioReferenceFormsHttpTest extends EmbeddedPostgresTest {
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
        unit = users.unit("refs-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "studio.lists", Set.of("view", "manage"),
                                        "md.settings", Set.of("update")),
                                unit)
                        .login());
    }

    @Test
    void aVisitNamesItsClientAndResultAndStaysInsideTheOrgUnitOfItsAuthor() throws Exception {
        String clients = forms.activate(publisher, forms.clients("clients_" + tag));
        String visits = forms.activate(publisher, forms.visits("visits_" + tag, clients));
        Map<String, Set<String>> rights =
                Map.of(clients, Set.of("view", "create"), visits, Set.of("view", "create", "update"));
        TestUser author = users.withRights(rights, unit);
        TestSession writer = TestSession.signIn(context, author.login());
        long client = forms.create(writer, clients, Map.of("name", "Ромашка", "code", "romashka"));
        long visit = forms.create(
                writer,
                visits,
                Map.of(
                        "name",
                        "Визит 1",
                        "code",
                        "v1",
                        "client",
                        client,
                        "visitdate",
                        "2026-10-17",
                        "result",
                        "done",
                        "comment",
                        "Всё хорошо"));

        Map<String, Object> read = TestSession.object(writer.send(get("/api/v1/entities/" + visits + "/" + visit)));
        assertThat(read).containsEntry("result", "done").containsEntry("orgUnit", (int) unit);
        assertThat(labels(read)).containsEntry("client", "Ромашка");
        assertThat(TestSession.object(writer.send(get("/api/v1/entities/" + visits)))
                        .toString())
                .contains("Ромашка");

        MockHttpServletResponse wrong = writer.send(
                post("/api/v1/entities/" + visits),
                Map.of("name", "Визит 2", "code", "v2", "client", client, "visitdate", "2026-10-18", "result", "x"));
        assertThat(wrong.getStatus()).isEqualTo(422);
        assertThat(wrong.getContentAsString()).contains("result");
        MockHttpServletResponse missing = writer.send(
                post("/api/v1/entities/" + visits),
                Map.of(
                        "name",
                        "Визит 3",
                        "code",
                        "v3",
                        "client",
                        987654321,
                        "visitdate",
                        "2026-10-18",
                        "result",
                        "done"));
        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(missing.getContentAsString()).contains("error.field.ref_not_found");

        Map<String, Object> meta = TestSession.object(writer.send(get("/api/v1/form-meta/" + visits)));
        assertThat(meta.toString()).contains("done", "later", "/entities/" + clients);

        TestSession other = TestSession.signIn(
                context,
                users.withRights(rights, users.unit("refs-other-" + tag)).login());
        assertThat(TestSession.object(other.send(get("/api/v1/entities/" + visits)))
                        .get("items"))
                .isEqualTo(List.of());
        assertThat(other.send(get("/api/v1/entities/" + visits + "/" + visit)).getStatus())
                .isEqualTo(404);
        assertThat(other.send(get("/api/v1/history/" + visits + "/" + visit)).getStatus())
                .isEqualTo(404);
        TestSession colleague =
                TestSession.signIn(context, users.withRights(rights, unit).login());
        assertThat(colleague
                        .send(get("/api/v1/entities/" + visits + "/" + visit))
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aRequestTakesATypeFromAReferenceListAndAResponsibleUserByName() throws Exception {
        String kinds = "kinds_" + tag;
        forms.list(publisher, kinds, List.of("repair", "delivery", "old"));
        forms.deactivate(publisher, kinds, "old");
        String clients = forms.activate(publisher, forms.clients("rclients_" + tag));
        String requests = forms.activate(publisher, forms.requests("requests_" + tag, clients, kinds));
        TestUser author = users.withRights(
                Map.of(
                        clients,
                        Set.of("view", "create"),
                        requests,
                        Set.of("view", "create"),
                        "md.users",
                        Set.of("view")),
                unit);
        TestSession writer = TestSession.signIn(context, author.login());
        long client = forms.create(writer, clients, Map.of("name", "Лютик", "code", "lutik"));
        long request = forms.create(
                writer,
                requests,
                Map.of(
                        "name",
                        "Заявка 1",
                        "code",
                        "r1",
                        "client",
                        client,
                        "kind",
                        "repair",
                        "responsible",
                        author.id()));

        Map<String, Object> read = TestSession.object(writer.send(get("/api/v1/entities/" + requests + "/" + request)));
        assertThat(read).containsEntry("kind", "repair");
        assertThat(labels(read))
                .containsEntry("client", "Лютик")
                .containsEntry("responsible", "TEST " + author.login());

        MockHttpServletResponse inactive = writer.send(
                post("/api/v1/entities/" + requests),
                Map.of("name", "Заявка 2", "code", "r2", "client", client, "kind", "old"));
        assertThat(inactive.getStatus()).isEqualTo(422);
        assertThat(inactive.getContentAsString()).contains("error.field.ref_archived");
        assertThat(writer.send(
                                post("/api/v1/entities/" + requests),
                                Map.of("name", "Заявка 3", "code", "r3", "kind", "unknown"))
                        .getStatus())
                .isEqualTo(422);

        MockHttpServletResponse uzbek =
                writer.send(get("/api/v1/form-meta/" + requests).header("Accept-Language", "uz"));
        Map<String, Object> kind = field(TestSession.object(uzbek), "kind");
        assertThat(kind).containsEntry("type", "enum").containsEntry("options", List.of("repair", "delivery"));
        assertThat(kind.get("optionLabels"))
                .isEqualTo(Map.of("repair", "Turi repair", "delivery", "Turi delivery", "old", "Turi old"));
        MockHttpServletResponse listMeta =
                writer.send(get("/api/v1/query-meta/" + requests).header("Accept-Language", "en"));
        assertThat(listMeta.getContentAsString()).contains("Kind repair", "Kind old");
        MockHttpServletResponse filtered = writer.send(get("/api/v1/entities/" + requests)
                .param("filter", "[{\"field\":\"kind\",\"op\":\"eq\",\"value\":\"repair\"}]"));
        assertThat(TestSession.object(filtered).get("items").toString()).contains("Заявка 1");

        TestSession blind = TestSession.signIn(
                context,
                users.withRights(Map.of(clients, Set.of("view"), requests, Set.of("view", "create")), unit)
                        .login());
        MockHttpServletResponse noUsers = blind.send(
                post("/api/v1/entities/" + requests),
                Map.of("name", "Заявка 4", "code", "r4", "kind", "repair", "responsible", author.id()));
        assertThat(noUsers.getStatus()).isEqualTo(422);
        assertThat(noUsers.getContentAsString()).contains("error.field.ref_not_found");
    }

    @Test
    void aTargetMustExistWhenTheFormIsActivated() throws Exception {
        MockHttpServletResponse refused = forms.tryActivate(
                publisher, forms.requests("orphans_" + tag, "studio.missing_" + tag, "nolist_" + tag));
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString())
                .contains("error.studio.target_not_found")
                .contains("fields[0].target.code")
                .contains("fields[1].target.code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> meta, String key) {
        return ((List<Map<String, Object>>) meta.get("fields"))
                .stream()
                        .filter(field -> key.equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labels(Map<String, Object> record) {
        return (Map<String, Object>) record.get("labels");
    }
}
