package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.section;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.tab;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

/**
 * Format 4 of a live form (ADR-0041): sections and tabs on the record's card and form, the record named by a field the
 * administrator chose or by its number instead of the system name and code — on its card, in references to it — and a
 * record of a form scoped by org unit moved between the units of the mover's scope only.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioLayoutTitleHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private MdScopeService scopes;

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
        unit = users.unit("layout-" + tag);
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
    void aFormNamedByItsFieldShowsItsTabsAndNamesItsRecordsInReferences() throws Exception {
        String clients = forms.activate(publisher, clients("tclients_" + tag));
        Map<String, Object> meta = meta(clients);
        assertThat(meta.get("titleField")).isEqualTo("title");
        assertThat(keys(meta, "layout")).containsExactly("general", "contacts");
        assertThat(keys(meta, "tabs")).containsExactly("main", "more", "history");
        assertThat(fields(meta)).contains("title", "phone").doesNotContain("name", "code", "recordNumber");

        String visits = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "tvisits_" + tag,
                                "Визиты",
                                "all",
                                List.of(field(
                                        "client",
                                        "ref",
                                        true,
                                        Map.of("target", Map.of("kind", "form", "code", clients))))),
                        null,
                        null));
        Map<String, Object> numbered = meta(visits);
        assertThat(numbered.get("titleField")).isEqualTo("recordNumber");
        assertThat(keys(numbered, "layout")).containsExactly("main");

        TestSession writer = TestSession.signIn(
                context,
                users.withRights(Map.of(clients, Set.of("view", "create"), visits, Set.of("view", "create")), unit)
                        .login());
        long client = forms.create(writer, clients, Map.of("title", "Ромашка", "phone", "+998"));
        long first = forms.create(writer, visits, Map.of("client", client));
        long second = forms.create(writer, visits, Map.of("client", client));
        Map<String, Object> record = TestSession.object(writer.send(get("/api/v1/entities/" + visits + "/" + first)));
        assertThat(record.get("recordNumber")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) record.get("labels");
        assertThat(labels).containsEntry("client", "Ромашка");
        assertThat(TestSession.object(writer.send(get("/api/v1/entities/" + visits + "/" + second)))
                        .get("recordNumber"))
                .isEqualTo(2);
        MockHttpServletResponse nameless = writer.send(post("/api/v1/entities/" + clients), Map.of("phone", "1"));
        assertThat(nameless.getStatus()).isEqualTo(422);
        assertThat(nameless.getContentAsString()).contains("title");
    }

    @Test
    void theLayoutAndTheTitleChangeFreelyButTheSystemNameIsKeptOrGoneForGood() throws Exception {
        String code = forms.activate(publisher, clients("vclients_" + tag));
        Map<String, Object> moved = forms.format4(
                forms.plain("vclients_" + tag, "Клиенты", "all", clientFields()),
                "phone",
                Map.of("sections", List.of(section("all", null, "phone", "title"))));
        assertThat(version(code, "\"1\"", moved).getStatus()).isEqualTo(422);
        Map<String, Object> phoneTitle = forms.format4(
                forms.plain(
                        "vclients_" + tag,
                        "Клиенты",
                        "all",
                        List.of(field("title", "text", true, true), field("phone", "text", true, true))),
                "phone",
                Map.of("sections", List.of(section("all", null, "phone", "title"))));
        MockHttpServletResponse required = version(code, "\"1\"", phoneTitle);
        assertThat(required.getStatus()).isEqualTo(422);
        assertThat(required.getContentAsString()).contains("change_required");
        Map<String, Object> retitled = forms.format4(
                forms.plain("vclients_" + tag, "Клиенты", "all", clientFields()),
                null,
                Map.of("sections", List.of(section("all", null, "phone", "title"))));
        MockHttpServletResponse ok = version(code, "\"1\"", retitled);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(201);
        Map<String, Object> meta = meta(code);
        assertThat(meta.get("titleField")).isEqualTo("recordNumber");
        assertThat(meta.get("tabs")).isNull();

        String named = forms.activate(publisher, forms.clients("nclients_" + tag));
        Map<String, Object> dropped = forms.format4(forms.clients("nclients_" + tag), null, null);
        MockHttpServletResponse removed = version(named, "\"1\"", dropped);
        assertThat(removed.getStatus()).isEqualTo(422);
        assertThat(removed.getContentAsString()).contains("change_removed");
        Map<String, Object> kept = forms.format4(
                forms.clients("nclients_" + tag),
                "name",
                Map.of("sections", List.of(section("contacts", null, "phone"))));
        MockHttpServletResponse keeps = version(named, "\"1\"", kept);
        assertThat(keeps.getStatus()).as(keeps.getContentAsString()).isEqualTo(201);
        Map<String, Object> keptMeta = meta(named);
        assertThat(keptMeta.get("titleField")).isEqualTo("name");
        assertThat(keys(keptMeta, "layout")).containsExactly("contacts");
    }

    @Test
    void aRecordMovesOnlyBetweenUnitsOfTheMoversScopeAndTheMoveIsAudited() throws Exception {
        long other = users.unit("other-" + tag);
        long outside = users.unit("outside-" + tag);
        String code = forms.activate(
                publisher,
                forms.format4(
                        forms.plain("units_" + tag, "Визиты", "org_unit", List.of(field("title", "text", true, true))),
                        "title",
                        null));
        Map<String, Set<String>> rights = Map.of(code, Set.of("view", "create", "update"));
        TestUser manager = users.withRights(rights, unit);
        scopeRepository.replaceUserOrgUnits(manager.id(), List.of(unit, other));
        scopes.recalculateFor(manager.id());
        TestSession managing = TestSession.signIn(context, manager.login());
        List<Object> offered = TestSession.array(managing.send(get("/api/v1/iam/org-units/scope")));
        assertThat(offered)
                .extracting(item -> ((Number) ((Map<?, ?>) item).get("id")).longValue())
                .containsExactlyInAnyOrder(unit, other);

        long id = forms.create(managing, code, Map.of("title", "Визит"));
        MockHttpServletResponse away = managing.send(
                patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"1\""), Map.of("orgUnit", outside));
        assertThat(away.getStatus()).isEqualTo(422);
        assertThat(away.getContentAsString()).contains("out_of_scope");
        MockHttpServletResponse moved = managing.send(
                patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"1\""), Map.of("orgUnit", other));
        assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
        assertThat(((Number) TestSession.object(moved).get("orgUnit")).longValue())
                .isEqualTo(other);
        String history =
                managing.send(get("/api/v1/history/" + code + "/" + id)).getContentAsString();
        assertThat(history).contains("orgUnit");

        // An author who sees their own records wherever they lie may not take one out of a unit they do not hold.
        TestUser author = users.withRights(rights, other);
        long role = jdbc.sql("select role_id from md_user_roles where user_id = :user")
                .param("user", author.id())
                .query(Long.class)
                .single();
        scopeRepository.setRoleRule(role, MdScopeService.RULE_SELF);
        scopes.recalculateFor(author.id());
        TestSession writing = TestSession.signIn(context, author.login());
        long own = forms.create(writing, code, Map.of("title", "Свой"));
        MockHttpServletResponse taken = managing.send(
                patch("/api/v1/entities/" + code + "/" + own).header("If-Match", "\"1\""), Map.of("orgUnit", unit));
        assertThat(taken.getStatus()).as(taken.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse back = writing.send(
                patch("/api/v1/entities/" + code + "/" + own).header("If-Match", "\"2\""), Map.of("orgUnit", other));
        assertThat(back.getStatus()).isEqualTo(422);
        assertThat(back.getContentAsString()).contains("out_of_scope");
    }

    /** The form as a viewer of its records reads it. */
    private Map<String, Object> meta(String code) throws Exception {
        TestSession viewer = TestSession.signIn(
                context, users.withRights(Map.of(code, Set.of("view")), unit).login());
        MockHttpServletResponse answer = viewer.send(get("/api/v1/form-meta/" + code));
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return TestSession.object(answer);
    }

    private Map<String, Object> clients(String name) {
        return forms.format4(
                forms.plain(name, "Клиенты", "all", clientFields()),
                "title",
                Map.of(
                        "tabs",
                        List.of(tab("main"), tab("more")),
                        "sections",
                        List.of(section("general", "main", "title"), section("contacts", "more", "phone"))));
    }

    private static List<Object> clientFields() {
        return List.of(field("title", "text", true, true), field("phone", "text", false, true));
    }

    private MockHttpServletResponse version(String code, String revision, Map<String, Object> declaration)
            throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        if (prepared.getStatus() != 200) return prepared;
        return publisher.send(
                post("/api/v1/studio/forms/" + code + "/versions").header("If-Match", revision),
                TestSession.object(prepared),
                null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> keys(Map<String, Object> meta, String part) {
        return ((List<Map<String, Object>>) meta.get(part))
                .stream().map(item -> (String) item.get("key")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(Map<String, Object> meta) {
        return ((List<Map<String, Object>>) meta.get("fields"))
                .stream().map(item -> (String) item.get("key")).toList();
    }
}
