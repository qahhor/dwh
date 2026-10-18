package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.report.export.ReportExportJob;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The file fields of a live form (ADR-0043, format 7): a record keeps the ids of files uploaded through the files module
 * — one, or several up to the field's limit — and reads them back with their names; a file is downloaded through its
 * record by whoever may read the record, and by nobody else; a save may attach only the saver's own uploads or the
 * record's own files; replacing and clearing a field detaches the files, deleting the record detaches them all; the
 * export writes file names, the webhook the files' metadata, the history the change.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioFileFieldsHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ReportExportJob exportJob;

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long home;
    private String code;
    private TestSession writer;
    private TestSession neighbour;
    private TestSession stranger;
    private TestSession outsider;
    private TestSession publisher;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        home = users.unit("files-home-" + tag);
        long other = users.unit("files-other-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.forms", Set.of("publish"), "md.settings", Set.of("update")), home)
                        .login());
        code = forms.activate(publisher, declaration("claims_" + tag));
        Map<String, Set<String>> rights =
                Map.of(code, Set.of("view", "create", "update", "delete"), "mf.files", Set.of("upload", "view"));
        writer = TestSession.signIn(context, users.withRights(rights, home).login());
        neighbour = TestSession.signIn(context, users.withRights(rights, home).login());
        stranger = TestSession.signIn(context, users.withRights(rights, other).login());
        outsider = TestSession.signIn(
                context,
                users.withRights(Map.of("mf.files", Set.of("upload")), home).login());
    }

    @Test
    void aRecordKeepsItsFilesAndOnlyItsReadersDownloadThem() throws Exception {
        UUID act = upload(writer, "act.txt", "the act");
        UUID first = upload(writer, "first.txt", "first");
        long id = forms.create(writer, code, Map.of("subject", "Претензия", "act", act, "docs", List.of(first)));

        Map<String, Object> record = TestSession.object(writer.send(get("/api/v1/entities/" + code + "/" + id)));
        assertThat(String.valueOf(record.get("act"))).contains("name=act.txt").contains("id=" + act);
        assertThat(String.valueOf(record.get("docs"))).contains("name=first.txt");
        assertThat(attachments(id)).containsExactlyInAnyOrder("act:" + act, "docs:" + first);

        MockHttpServletResponse file = neighbour.send(get(fileUrl(id, act)));
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        assertThat(file.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("the act");
        assertThat(file.getHeader("Content-Disposition")).contains("act.txt");

        assertThat(stranger.send(get("/api/v1/entities/" + code + "/" + id)).getStatus())
                .isEqualTo(404);
        assertThat(stranger.send(get(fileUrl(id, act))).getStatus()).isEqualTo(404);
        assertThat(outsider.send(get(fileUrl(id, first))).getStatus()).isEqualTo(404);
        assertThat(stranger.send(get("/api/v1/files/" + act + "/download")).getStatus())
                .isIn(403, 404);
    }

    @Test
    void aSaveAttachesOnlyOwnUploadsWithinTheLimitsAndReplacingDetaches() throws Exception {
        UUID act = upload(writer, "act.txt", "a");
        UUID first = upload(writer, "first.txt", "1");
        UUID second = upload(writer, "second.txt", "2");
        UUID third = upload(writer, "third.txt", "3");
        UUID foreign = upload(stranger, "foreign.txt", "x");

        assertRefused(Map.of("subject", "S", "act", foreign), "error.field.file_not_found");
        assertRefused(Map.of("subject", "S", "docs", List.of(first, second, third)), "error.field.too_many");
        assertRefused(Map.of("subject", "S", "docs", List.of(first, first)), "error.field.file_invalid");
        assertRefused(Map.of("subject", "S", "act", List.of(act)), "error.field.value_type");

        long id = forms.create(writer, code, Map.of("subject", "S", "act", act, "docs", List.of(first)));
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("act", null);
        change.put("docs", List.of(first, second));
        MockHttpServletResponse replaced =
                writer.send(patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"1\""), change);
        assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
        assertThat(attachments(id)).containsExactlyInAnyOrder("docs:" + first, "docs:" + second);

        // The neighbour may keep the record's own files in a save, but not attach the writer's other uploads.
        MockHttpServletResponse kept = neighbour.send(
                patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"2\""),
                Map.of("subject", "S2", "docs", List.of(second)));
        assertThat(kept.getStatus()).as(kept.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse borrowed = neighbour.send(
                patch("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"3\""),
                Map.of("docs", List.of(second, third)));
        assertThat(borrowed.getStatus()).isEqualTo(422);
        assertThat(borrowed.getContentAsString()).contains("error.field.file_not_found");

        String history =
                writer.send(get("/api/v1/history/" + code + "/" + id)).getContentAsString(StandardCharsets.UTF_8);
        assertThat(history).contains("docs").contains("second.txt");

        MockHttpServletResponse deleted =
                writer.send(delete("/api/v1/entities/" + code + "/" + id).header("If-Match", "\"3\""));
        assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(204);
        assertThat(attachments(id)).isEmpty();
    }

    @Test
    void theExportWritesFileNamesAndTheWebhookTheFilesMetadata() throws Exception {
        long creator = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        long subscription = subscriptions
                .create("files " + tag, "https://hooks.example.test/f", "k", List.of(code + ".created"), "all", creator)
                .id();
        UUID act = upload(writer, "act.txt", "a");
        UUID first = upload(writer, "first.txt", "1");
        UUID second = upload(writer, "second.txt", "2");
        long id = forms.create(writer, code, Map.of("subject", "Экспорт", "act", act, "docs", List.of(first, second)));

        String payload = jdbc.sql("select payload::text from kwh_outbox where subscription_id = :s")
                .param("s", subscription)
                .query(String.class)
                .single();
        assertThat(payload)
                .contains("\"name\": \"act.txt\"")
                .contains("\"contentType\"")
                .contains(first.toString());
        assertThat(payload).doesNotContain("files/" + act);
        jdbc.sql("delete from kwh_subscriptions where id = :s")
                .param("s", subscription)
                .update();

        MockHttpServletResponse queued = writer.send(
                post("/api/v1/exports"),
                Map.of("list", code, "columns", List.of("subject", "act", "docs"), "lang", "ru"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String export = (String) TestSession.object(queued).get("id");
        run(export);
        MockHttpServletResponse xlsx = writer.send(get("/api/v1/exports/" + export + "/file"));
        assertThat(xlsx.getStatus()).as(xlsx.getContentAsString()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < 3; c++) cells.add(rows.get(1).getCellText(c));
            assertThat(cells).containsExactly("Экспорт", "act.txt", "first.txt, second.txt");
        }
        assertThat(id).isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVersionAddsAFileFieldButKeepsOneFileOrSeveral() throws Exception {
        Map<String, Object> grown = declaration("claims_" + tag);
        List<Object> fields = new ArrayList<>((List<Object>) grown.get("fields"));
        fields.add(StudioFormFixtures.field("photo", "file", false, Map.<String, Object>of()));
        grown.put("fields", fields);
        MockHttpServletResponse added = version(grown, "\"1\"");
        assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);

        Map<String, Object> single = declaration("claims_" + tag);
        List<Object> narrowed = new ArrayList<>((List<Object>) single.get("fields"));
        narrowed.set(2, StudioFormFixtures.field("docs", "file", false, Map.<String, Object>of()));
        narrowed.add(StudioFormFixtures.field("photo", "file", false, Map.<String, Object>of()));
        single.put("fields", narrowed);
        MockHttpServletResponse refused = version(single, "\"2\"");
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("change_files_multiple");
    }

    private MockHttpServletResponse version(Map<String, Object> declaration, String revision) throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        return publisher.send(
                post("/api/v1/studio/forms/" + code + "/versions").header("If-Match", revision),
                TestSession.object(prepared),
                null);
    }

    private void assertRefused(Map<String, Object> body, String key) throws Exception {
        MockHttpServletResponse refused = writer.send(post("/api/v1/entities/" + code), body);
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains(key);
    }

    private String fileUrl(long id, UUID file) {
        return "/api/v1/entities/" + code + "/" + id + "/files/" + file;
    }

    private List<String> attachments(long id) {
        return jdbc.sql("""
                        select field_key || ':' || file_id from mf_record_files
                        where entity = :entity and record_id = :id""")
                .param("entity", code)
                .param("id", id)
                .query(String.class)
                .list();
    }

    private static UUID upload(TestSession session, String name, String content) throws Exception {
        var request = multipart("/api/v1/files/upload")
                .file(new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = session.upload(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 201);
        return UUID.fromString((String) TestSession.object(response).get("id"));
    }

    private Map<String, Object> declaration(String name) {
        Map<String, Object> declaration = forms.plain(
                name,
                "Претензии",
                "org_unit",
                List.<Object>of(
                        StudioFormFixtures.field("subject", "text", true, Map.<String, Object>of("maxLength", 200)),
                        StudioFormFixtures.field(
                                "act",
                                "file",
                                false,
                                Map.<String, Object>of(
                                        "files", Map.of("multiple", false, "contentTypes", List.of("text/plain")))),
                        StudioFormFixtures.field(
                                "docs",
                                "file",
                                false,
                                Map.<String, Object>of("files", Map.of("multiple", true, "maxFiles", 2)))));
        Map<String, Object> copy = new LinkedHashMap<>(declaration);
        copy.put("formatVersion", 7);
        return copy;
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
