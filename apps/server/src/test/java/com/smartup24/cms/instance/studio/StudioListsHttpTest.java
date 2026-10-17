package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.instance.studio.service.StudioListEnums;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * The reference lists an administrator keeps (ADR-0041): created, renamed and filled over HTTP with their own rights,
 * revisions and audit; codes are permanent and nothing is deleted; the items are the enumeration of the list, named in
 * the language of the work and refreshed on every change.
 */
class StudioListsHttpTest extends EmbeddedPostgresTest {
    private static final String LISTS = "/api/v1/studio/lists";
    private static final Map<String, String> REGION = Map.of("ru", "Регионы", "uz", "Hududlar", "en", "Regions");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityEnums enums;

    private TestSession manager;
    private TestSession reader;
    private String code;

    @BeforeEach
    void setup() throws Exception {
        var users = TestUsers.of(context);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long unit = users.unit("lists-" + tag);
        manager = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.lists", Set.of("view", "manage")), unit)
                        .login());
        reader = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.lists", Set.of("view")), unit).login());
        code = "regions_" + tag;
    }

    @Test
    void aListIsCreatedRenamedAndReadWithItsRevisionAndOnlyByItsRights() throws Exception {
        MockHttpServletResponse created = manager.send(post(LISTS), Map.of("code", code, "name", REGION));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        assertThat(created.getHeader("Location")).isEqualTo(LISTS + "/" + code);
        assertThat(created.getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(list(created)).containsEntry("code", code).containsEntry("items", 0);

        assertThat(manager.send(post(LISTS), Map.of("code", code, "name", REGION))
                        .getStatus())
                .isEqualTo(409);
        assertThat(reader.send(post(LISTS), Map.of("code", code + "x", "name", REGION))
                        .getStatus())
                .isEqualTo(403);
        MockHttpServletResponse blank =
                manager.send(post(LISTS), Map.of("code", code + "b", "name", Map.of("ru", " ", "uz", "a", "en", "b")));
        assertThat(blank.getStatus()).isEqualTo(422);
        assertThat(blank.getContentAsString()).contains("name.ru");
        assertThat(manager.send(post(LISTS), Map.of("code", "Bad", "name", REGION))
                        .getStatus())
                .isEqualTo(422);

        Map<String, String> renamed = Map.of("ru", "Области", "uz", "Viloyatlar", "en", "Provinces");
        assertThat(manager.send(put(LISTS + "/" + code), Map.of("name", renamed))
                        .getStatus())
                .isEqualTo(428);
        assertThat(manager.send(put(LISTS + "/" + code).header("If-Match", "\"5\""), Map.of("name", renamed))
                        .getStatus())
                .isEqualTo(409);
        MockHttpServletResponse ok =
                manager.send(put(LISTS + "/" + code).header("If-Match", "\"1\""), Map.of("name", renamed));
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(ok.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(reader.send(put(LISTS + "/" + code).header("If-Match", "\"2\""), Map.of("name", REGION))
                        .getStatus())
                .isEqualTo(403);

        MockHttpServletResponse read = reader.send(get(LISTS + "/" + code));
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(read.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(list(read).get("name")).isEqualTo(renamed);
        assertThat(reader.send(get(LISTS + "/" + code + "zz")).getStatus()).isEqualTo(404);
        assertThat(reader.send(get(LISTS + "/Not-A-Code")).getStatus()).isEqualTo(404);
        MockHttpServletResponse page = reader.send(get(LISTS).param("limit", "100"));
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentAsString()).contains(code);
        assertThat(reader.send(get(LISTS).param("limit", "101")).getStatus()).isEqualTo(422);
        assertThat(audited("studio_lists")).isEqualTo(2);
    }

    @Test
    void itemsKeepTheirCodesAreDeactivatedNotDeletedAndNameTheEnumerationInEveryLanguage() throws Exception {
        assertThat(manager.send(post(LISTS), Map.of("code", code, "name", REGION))
                        .getStatus())
                .isEqualTo(201);
        MockHttpServletResponse north = manager.send(post(LISTS + "/" + code + "/items"), item("north", 20));
        assertThat(north.getStatus()).as(north.getContentAsString()).isEqualTo(201);
        assertThat(north.getHeader("Location")).isEqualTo(LISTS + "/" + code + "/items/north");
        assertThat(manager.send(post(LISTS + "/" + code + "/items"), item("south", 10))
                        .getStatus())
                .isEqualTo(201);
        assertThat(manager.send(post(LISTS + "/" + code + "/items"), item("north", 30))
                        .getStatus())
                .isEqualTo(409);
        assertThat(reader.send(post(LISTS + "/" + code + "/items"), item("east", 30))
                        .getStatus())
                .isEqualTo(403);
        assertThat(manager.send(post(LISTS + "/" + code + "zz/items"), item("east", 30))
                        .getStatus())
                .isEqualTo(404);

        String reference = StudioListEnums.reference(code);
        EntityEnums.Items russian = RequestLanguage.with("ru", () -> enums.all(reference));
        assertThat(russian.names()).containsExactly(Map.entry("south", "Юг south"), Map.entry("north", "Юг north"));
        assertThat(RequestLanguage.with("uz", () -> enums.all(reference)).names())
                .containsEntry("north", "Janub north");

        Map<String, Object> off =
                Map.of("name", Map.of("ru", "Север", "uz", "Shimol", "en", "North"), "sortOrder", 5, "active", false);
        String path = LISTS + "/" + code + "/items/north";
        assertThat(manager.send(put(path), off).getStatus()).isEqualTo(428);
        assertThat(manager.send(put(path).header("If-Match", "\"9\""), off).getStatus())
                .isEqualTo(409);
        MockHttpServletResponse changed = manager.send(put(path).header("If-Match", "\"1\""), off);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(changed.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(TestSession.object(changed)).containsEntry("active", false);
        assertThat(manager.send(put(LISTS + "/" + code + "/items/west").header("If-Match", "\"1\""), off)
                        .getStatus())
                .isEqualTo(404);

        EntityEnums.Items after = RequestLanguage.with("en", () -> enums.all(reference));
        assertThat(after.names()).containsExactly(Map.entry("north", "North"), Map.entry("south", "South south"));
        assertThat(after.archived()).containsExactly("north");
        assertThat(after.offered()).containsOnlyKeys("south");

        MockHttpServletResponse detail = reader.send(get(LISTS + "/" + code));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) TestSession.object(detail).get("items");
        assertThat(items).extracting(row -> row.get("code")).containsExactly("north", "south");
        assertThat(list(detail)).containsEntry("items", 2).containsEntry("activeItems", 1);
        assertThat(audited("studio_list_items")).isEqualTo(3);

        assertThatThrownBy(() -> jdbc.sql("delete from studio_list_items where code = 'south'")
                        .update())
                .rootCause()
                .hasMessageContaining("permanent");
        assertThatThrownBy(() -> jdbc.sql("update studio_list_items set code = 'west' where code = 'south'")
                        .update())
                .rootCause()
                .hasMessageContaining("permanent");
        assertThatThrownBy(() -> jdbc.sql("delete from studio_lists where code = :code")
                        .param("code", code)
                        .update())
                .rootCause()
                .hasMessageContaining("permanent");
    }

    private static Map<String, Object> item(String code, int order) {
        return Map.of(
                "code",
                code,
                "name",
                Map.of("ru", "Юг " + code, "uz", "Janub " + code, "en", "South " + code),
                "sortOrder",
                order);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> list(MockHttpServletResponse response) throws Exception {
        return (Map<String, Object>) TestSession.object(response).get("list");
    }

    private long audited(String table) {
        Long count = jdbc.sql("""
                        select count(*) from audit_log where table_name = :table
                          and (new_row ->> 'code' = :code or new_row ->> 'list' = :code)
                        """)
                .param("table", table)
                .param("code", code)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }
}
