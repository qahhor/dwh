package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.support.EmbeddedPostgresTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The file list through the field registry: {@code GET /api/v1/files} (keyset over UUID keys, scope, search). */
class MfFileListControllerTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private MdUserService users;
    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf, long userId) {
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("страницы курсором по UUID без повторов, поиск по имени, сортировка по размеру, «только мои»")
    void filesPageThroughTheRegistry() throws Exception {
        Session admin = login(user("chief_admin"));
        Session other = login(user("chief_admin"));
        String tag = "list-" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 1; i <= 5; i++) {
            insertFile(tag + "-" + i + ".txt", i * 100L, admin.userId());
        }
        insertFile(tag + "-foreign.txt", 1L, other.userId());
        String mine = URLEncoder.encode("[{\"field\":\"originalName\",\"op\":\"starts_with\",\"value\":\"" + tag + "\"}]",
                StandardCharsets.UTF_8);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = fetch(admin, "/api/v1/files?limit=2&filter=" + mine + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(read(page, "$.items[*].originalName"));
            cursor = (String) ((Map<String, Object>) read(page, "$")).get("nextCursor");
        } while (cursor != null);
        assertThat(seen).hasSize(6).doesNotHaveDuplicates();

        var bySize = fetch(admin, "/api/v1/files?sort=-sizeBytes&filter=" + mine);
        assertThat((List<String>) read(bySize, "$.items[*].originalName")).startsWith(tag + "-5.txt", tag + "-4.txt");

        var search = fetch(admin, "/api/v1/files?q=" + tag + "-3");
        assertThat((List<String>) read(search, "$.items[*].originalName")).containsExactly(tag + "-3.txt");

        var onlyMine = fetch(admin, "/api/v1/files?scope=mine&filter=" + mine);
        assertThat((Integer) read(onlyMine, "$.totalEstimated")).isEqualTo(5);
        assertThat((List<String>) read(onlyMine, "$.items[*].originalName")).doesNotContain(tag + "-foreign.txt");

        var meta = fetch(admin, "/api/v1/query-meta/mf.files");
        assertThat((String) read(meta, "$.defaultSort")).isEqualTo("-createdAt");
    }

    private void insertFile(String name, long size, long ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into mf_files (id, sha256, original_name, size_bytes, mime_type,
                                              storage_bucket, storage_key, created_by)
                        values (:id, :sha, :name, :size, 'text/plain', 'instance-files', :key, :ownerId)
                        """)
                .param("id", id)
                .param("sha", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("name", name)
                .param("size", size)
                .param("key", "test/" + id)
                .param("ownerId", ownerId)
                .update();
    }

    private String user(String role) {
        String login = "files-" + UUID.randomUUID().toString().substring(0, 8);
        Long systemId = jdbc.sql("select id from md_users where login = 'system'").query(Long.class).single();
        Long roleId = jdbc.sql("select id from md_roles where pcode = :role").param("role", role)
                .query(Long.class).single();
        users.createUser("TEST " + login, login, login + "@test.local", null, PASSWORD, null, "ru", "UTC", null,
                Map.of(), false, false, List.of(roleId), systemId);
        return login;
    }

    private Session login(String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(
                                Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        }
        long userId = jdbc.sql("select id from md_users where login = :login").param("login", login).query(Long.class).single();
        return new Session(session, csrf, userId);
    }

    private MockHttpServletResponse fetch(Session s, String url) throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(url))
                .cookie(s.session(), s.csrf())).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private static <T> T read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path);
    }
}
