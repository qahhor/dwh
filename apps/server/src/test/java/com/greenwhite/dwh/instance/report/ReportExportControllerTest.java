package com.greenwhite.dwh.instance.report;

import com.greenwhite.dwh.instance.config.idempotency.IdempotencyFilter;
import com.greenwhite.dwh.instance.fnd.jobs.FndJobRunner;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.report.export.ReportExportService;
import com.greenwhite.dwh.instance.support.EmbeddedPostgresTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** HTTP-проверка асинхронной выгрузки списков в xlsx и журнала выгрузок (ADR-0018, роадмап п. 22). */
class ReportExportControllerTest extends EmbeddedPostgresTest {

    private static final String BASE = "/api/v1/exports";
    private static final String PASSWORD = "StrongPassword2026!";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private MdUserService users;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private FndJobRunner jobs;
    @Autowired
    private ReportExportService exports;

    private MockMvc mvc;

    private record Session(String login, Cookie session, Cookie csrf) {
    }

    @BeforeEach
    void setUp() {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity());
        IdempotencyFilter idempotency = wac.getBeanProvider(IdempotencyFilter.class).getIfAvailable();
        if (idempotency != null) {
            builder.addFilters(idempotency);
        }
        mvc = builder.build();
        jdbc.sql("delete from fnd_job_queue").update();
    }

    @Test
    @DisplayName("выгрузка ставится в очередь, задание пишет xlsx с колонками и сортировкой экрана, журнал отдаёт файл")
    void exportIsQueuedWrittenAndDownloaded() throws Exception {
        Session admin = login(user("chief_admin"));
        String tag = "test.exp" + UUID.randomUUID().toString().substring(0, 6);
        for (String suffix : List.of("c", "a", "b")) {
            var created = send(admin, post("/api/v1/upl/sources"), source(tag + "." + suffix, "TEST export " + suffix));
            assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        }

        var queued = send(admin, post(BASE), Map.of("list", "upl.sources", "sort", "code", "q", tag,
                "columns", List.of("code", "periodicity"), "lang", "ru"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String id = read(queued, "$.id");
        assertThat((String) read(queued, "$.state")).isEqualTo("queued");

        assertThat(jobs.runQueued()).isEqualTo(1);

        var journal = send(admin, get(BASE), null);
        assertThat((String) read(journal, "$[0].id")).isEqualTo(id);
        assertThat((String) read(journal, "$[0].state")).isEqualTo("done");
        assertThat((Integer) read(journal, "$[0].rowsCount")).isEqualTo(3);
        var file = send(admin, get(BASE + "/" + id + "/file"), null);
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getHeader("Content-Disposition")).startsWith("attachment").contains("upl_sources_");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(List.of(rows.get(0).getCellText(0), rows.get(0).getCellText(1)))
                    .doesNotContain("upl.list.col.code").hasSize(2);
            assertThat(rows.stream().skip(1).map(row -> row.getCellText(0)).toList())
                    .containsExactly(tag + ".a", tag + ".b", tag + ".c");
            // A choice by its words, not its stored value.
            assertThat(rows.get(1).getCellText(1)).isNotEqualTo("month").isNotBlank();
        }
        assertThat(jdbc.sql("select count(*) from audit_log where table_name = 'report_exports' and row_pk = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("запрос проверяется сразу: список, фильтр, колонки, параметры; чужая выгрузка неотличима от несуществующей")
    void requestIsCheckedAtOnceAndExportsArePrivate() throws Exception {
        Session admin = login(user("chief_admin"));
        assertThat(send(admin, post(BASE), Map.of("list", "no.such.list")).getStatus()).isEqualTo(404);
        assertThat(send(admin, post(BASE), Map.of("list", "upl.sources", "filter", "[{\"field\":\"nope\",\"op\":\"eq\",\"value\":1}]"))
                .getStatus()).isEqualTo(422);
        var badColumn = send(admin, post(BASE), Map.of("list", "upl.sources", "columns", List.of("code", "secret")));
        assertThat(badColumn.getStatus()).isEqualTo(422);
        assertThat((List<String>) read(badColumn, "$.errors[*].field")).containsExactly("columns[1]");
        assertThat(send(admin, post(BASE), Map.of("list", "upl.sources", "options", Map.of("scope", "mine")))
                .getStatus()).isEqualTo(422);

        var own = send(admin, post(BASE), Map.of("list", "upl.sources"));
        String id = read(own, "$.id");
        jobs.runQueued();
        Session other = login(user("chief_admin"));
        assertThat(send(other, get(BASE + "/" + id + "/file"), null).getStatus()).isEqualTo(404);
        assertThat((List<Object>) read(send(other, get(BASE), null), "$")).isEmpty();
        assertThat(send(admin, get(BASE + "/not-a-uuid/file"), null).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("не больше трёх выгрузок в работе; без права на список — 404")
    void activeExportsAreLimitedAndListsNeedTheirRight() throws Exception {
        Session admin = login(user("chief_admin"));
        for (int i = 0; i < 3; i++) {
            assertThat(send(admin, post(BASE), Map.of("list", "upl.sources")).getStatus()).isEqualTo(202);
        }
        var busy = send(admin, post(BASE), Map.of("list", "upl.sources"));
        assertThat(busy.getStatus()).isEqualTo(409);
        assertThat((String) read(busy, "$.detail")).isEqualTo("EXPORT_BUSY");

        Session plain = login(user("user"));
        assertThat(send(plain, post(BASE), Map.of("list", "upl.sources")).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("права проверяются в момент выполнения: заблокированному выгрузка не пишется")
    void jobRunsWithTheOwnersCurrentRights() throws Exception {
        String login = user("chief_admin");
        Session s = login(login);
        String id = read(send(s, post(BASE), Map.of("list", "upl.sources")), "$.id");
        jdbc.sql("update md_users set state = 'P' where login = :login").param("login", login).update();

        jobs.runQueued();

        Map<String, Object> row = jdbc.sql("select state, error_code, storage_key from report_exports where public_id = cast(:id as uuid)")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("state", "failed").containsEntry("error_code", "EXPORT_FORBIDDEN");
        assertThat(row.get("storage_key")).isNull();
    }

    @Test
    @DisplayName("истёкшая выгрузка удаляется вместе с файлом")
    void expiredExportsAreCleanedUp() throws Exception {
        Session admin = login(user("chief_admin"));
        String id = read(send(admin, post(BASE), Map.of("list", "upl.sources")), "$.id");
        jobs.runQueued();
        jdbc.sql("update report_exports set expires_at = now() - interval '1 minute' where public_id = cast(:id as uuid)")
                .param("id", id).update();

        assertThat(exports.cleanup()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.sql("select count(*) from report_exports where public_id = cast(:id as uuid)")
                .param("id", id).query(Long.class).single()).isZero();
    }

    private static Map<String, Object> source(String code, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("ownerOrg", "TEST org");
        body.put("periodicity", "month");
        body.put("slaDays", 5);
        return body;
    }

    private String user(String role) {
        String login = "export-" + role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long systemId = jdbc.sql("select id from md_users where login = 'system'").query(Long.class).single();
        Long roleId = jdbc.sql("select id from md_roles where pcode = :role").param("role", role)
                .query(Long.class).single();
        users.createUser("TEST " + login, login, login + "@test.local", null, PASSWORD, null, "ru", "UTC", null,
                Map.of(), false, false, List.of(roleId), systemId);
        return login;
    }

    private Session login(String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json(Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        }
        assertThat(csrf).as("XSRF-TOKEN cookie").isNotNull();
        return new Session(login, session, csrf);
    }

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.cookie(s.session(), s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType("application/json").content(json(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request) throws Exception {
        return send(s, request, null);
    }

    private static <T> T read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path);
    }

    private static String json(Object value) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
    }
}
