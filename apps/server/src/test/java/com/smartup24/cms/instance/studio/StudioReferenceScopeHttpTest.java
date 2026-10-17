package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.report.export.ReportExportJob;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The data scope, the export and the versions of forms with references (ADR-0041): a form scoped to its owner shows
 * each author their own records only; the XLSX export names references and list items in the language asked; a new
 * version may add choices but keeps the targets, the choices records hold and the scope; a target that is no longer
 * served refuses new values without failing the request.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioReferenceScopeHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ReportExportJob exportJob;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long unit;
    private TestSession publisher;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        unit = users.unit("scope-" + tag);
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
    void aFormScopedToItsOwnerShowsEachAuthorTheirOwnRecords() throws Exception {
        Map<String, Object> declaration = forms.clients("own_" + tag);
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = new java.util.LinkedHashMap<>((Map<String, Object>) declaration.get("entity"));
        entity.put("scope", "owner");
        declaration.put("entity", entity);
        String code = forms.activate(publisher, declaration);
        Map<String, Set<String>> rights = Map.of(code, Set.of("view", "create"));
        TestSession first =
                TestSession.signIn(context, users.withRights(rights, unit).login());
        TestSession second =
                TestSession.signIn(context, users.withRights(rights, unit).login());
        long mine = forms.create(first, code, Map.of("name", "Моя", "code", "mine"));
        forms.create(second, code, Map.of("name", "Чужая", "code", "theirs"));

        String firstList = first.send(get("/api/v1/entities/" + code)).getContentAsString();
        assertThat(firstList).contains("Моя").doesNotContain("Чужая");
        assertThat(second.send(get("/api/v1/entities/" + code + "/" + mine)).getStatus())
                .isEqualTo(404);
    }

    @Test
    void theExportNamesReferencesAndListItemsInTheLanguageAsked() throws Exception {
        String kinds = "xkinds_" + tag;
        forms.list(publisher, kinds, List.of("repair", "delivery"));
        String clients = forms.activate(publisher, forms.clients("xclients_" + tag));
        String requests = forms.activate(publisher, forms.requests("xrequests_" + tag, clients, kinds));
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
        long client = forms.create(writer, clients, Map.of("name", "Василёк", "code", "vasilek"));
        forms.create(
                writer,
                requests,
                Map.of(
                        "name",
                        "Заявка",
                        "code",
                        "x1",
                        "client",
                        client,
                        "kind",
                        "delivery",
                        "responsible",
                        author.id()));

        MockHttpServletResponse queued = writer.send(
                post("/api/v1/exports"),
                Map.of("list", requests, "columns", List.of("name", "client", "kind", "responsible"), "lang", "en"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String id = (String) TestSession.object(queued).get("id");
        run(id);
        MockHttpServletResponse file = writer.send(get("/api/v1/exports/" + id + "/file"));
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(2);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < 4; c++) cells.add(rows.get(1).getCellText(c));
            assertThat(cells).containsExactly("Заявка", "Василёк", "Kind delivery", "TEST " + author.login());
        }
    }

    @Test
    void aVersionAddsChoicesButKeepsTargetsChoicesAndTheScopeAndALostTargetRefusesNewValues() throws Exception {
        String clients = forms.activate(publisher, forms.clients("vclients_" + tag));
        String visits = forms.activate(publisher, forms.visits("vvisits_" + tag, clients));

        Map<String, Object> grown = forms.visits("vvisits_" + tag, clients);
        options(grown).add(Map.of("code", "refused", "labels", Map.of("ru", "Отказ", "uz", "Rad", "en", "Refused")));
        MockHttpServletResponse ok = version(visits, "\"1\"", grown);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(201);

        Map<String, Object> dropped = forms.visits("vvisits_" + tag, clients);
        options(dropped).removeLast();
        MockHttpServletResponse refused = version(visits, "\"2\"", dropped);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("change_option_removed");

        Map<String, Object> widened = forms.visits("vvisits_" + tag, clients);
        options(widened).add(Map.of("code", "refused", "labels", Map.of("ru", "Отказ", "uz", "Rad", "en", "Refused")));
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = new java.util.LinkedHashMap<>((Map<String, Object>) widened.get("entity"));
        entity.put("scope", "all");
        widened.put("entity", entity);
        MockHttpServletResponse scope = version(visits, "\"2\"", widened);
        assertThat(scope.getStatus()).isEqualTo(422);
        assertThat(scope.getContentAsString()).contains("error.studio.scope_change");

        TestSession writer = TestSession.signIn(
                context,
                users.withRights(Map.of(clients, Set.of("view", "create"), visits, Set.of("view", "create")), unit)
                        .login());
        long client = forms.create(writer, clients, Map.of("name", "Мак", "code", "mak"));
        long visit = forms.create(
                writer,
                visits,
                Map.of(
                        "name",
                        "Визит",
                        "code",
                        "v1",
                        "client",
                        client,
                        "visitdate",
                        "2026-10-17",
                        "result",
                        "refused"));
        MockHttpServletResponse held = publisher.send(
                post("/api/v1/studio/quarantine"), Map.of("entityCode", clients, "reason", "test"), null);
        assertThat(held.getStatus()).as(held.getContentAsString()).isIn(200, 201);
        MockHttpServletResponse lost = writer.send(
                post("/api/v1/entities/" + visits),
                Map.of("name", "Визит 2", "code", "v2", "client", client, "visitdate", "2026-10-18", "result", "done"));
        assertThat(lost.getStatus()).isEqualTo(422);
        assertThat(lost.getContentAsString()).contains("error.field.ref_not_found");
        assertThat(writer.send(get("/api/v1/entities/" + visits + "/" + visit)).getStatus())
                .isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> options(Map<String, Object> declaration) {
        Map<String, Object> result = ((List<Map<String, Object>>) declaration.get("fields")).get(2);
        List<Object> options = new ArrayList<>((List<Object>) result.get("options"));
        result.put("options", options);
        return options;
    }

    private MockHttpServletResponse version(String code, String revision, Map<String, Object> declaration)
            throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        return publisher.send(
                post("/api/v1/studio/forms/" + code + "/versions").header("If-Match", revision),
                TestSession.object(prepared),
                null);
    }

    private void run(String id) throws Exception {
        String raw = jdbc.sql("""
                        select args::text from fnd_job_queue
                         where handler = 'report.export' and args ->> 'exportId' = :id
                        """).param("id", id).query(String.class).single();
        Map<String, Object> args = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                        exportJob.run(args);
                        assertThat(SecurityContext.isAuthenticated()).isFalse();
                    })
                    .get(30, TimeUnit.SECONDS);
        }
    }
}
