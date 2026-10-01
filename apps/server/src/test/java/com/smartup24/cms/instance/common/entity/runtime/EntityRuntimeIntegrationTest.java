package com.smartup24.cms.instance.common.entity.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.common.entity.event.EntityEventType;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * The general runtime end to end (ADR-0032, 6.3–6.9 and 12; plan 10/10, item 5.4), on a test entity with a rule, hooks,
 * an action and a reference to notes: the fixed order of a save — the hook sees checked values and may change them or
 * refuse, every problem of the body answers one 422, a reference is checked in its target's scope and archive — the
 * action, the event published once after the commit and none on a rollback, an afterCommit failure that does not change
 * the answer, and the module switch that hides an entity.
 */
@Import(EntityRuntimeFixture.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityRuntimeIntegrationTest extends EmbeddedPostgresTest {

    private static final String ITEMS = "/api/v1/entities/" + EntityRuntimeFixture.CODE;
    private static final String NOTES = "/api/v1/entities/ms.notes";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestUser owner;
    private TestUser other;
    private TestUser viewer;
    private TestSession ownerSession;
    private TestSession otherSession;

    @BeforeAll
    void setUp() throws Exception {
        for (String statement : EntityRuntimeFixture.DDL.split(";")) {
            jdbc.sql(statement).update();
        }
        TestUsers users = TestUsers.of(wac);
        long unit = users.unit("rt");
        Map<String, Set<String>> rights = Map.of("notes", Set.of("view", "create", "update", "delete"));
        owner = users.withRights(rights, unit);
        other = users.withRights(rights, unit);
        viewer = users.withRights(Map.of("notes", Set.of("view")), unit);
        ownerSession = TestSession.signIn(wac, owner.login());
        otherSession = TestSession.signIn(wac, other.login());
    }

    @AfterAll
    void dropTable() {
        jdbc.sql("drop table if exists " + EntityRuntimeFixture.TABLE).update();
    }

    @Test
    @DisplayName("5.4: beforeSave sees checked values and sets one; afterSave and afterCommit follow; one event")
    void hooksRunInTheirOrder() throws Exception {
        MockHttpServletResponse created = ownerSession.send(post(ITEMS), Map.of("title", "first item"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        Map<String, Object> record = TestSession.object(created);
        long id = number(record.get("id"));
        assertThat(record).containsEntry("code", "RT-FIRST-ITEM").containsEntry("starred", false);
        assertThat(created.getHeader("Location")).endsWith(ITEMS + "/" + id);
        assertThat(EntityRuntimeFixture.SAVES).contains("CREATE " + id);
        assertThat(EntityRuntimeFixture.COMMITTED).contains(id);
        assertThat(events(id)).extracting(event -> event.type()).containsExactly(EntityEventType.CREATED);

        MockHttpServletResponse renamed =
                ownerSession.send(patch(ITEMS + "/" + id).header("If-Match", "\"1\""), Map.of("title", "renamed"));
        assertThat(renamed.getStatus()).as(renamed.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(renamed)).containsEntry("code", "RT-RENAMED");
        assertThat(events(id))
                .extracting(event -> event.type())
                .containsExactly(EntityEventType.CREATED, EntityEventType.UPDATED);
        assertThat(events(id).getLast().changedFields()).contains("title", "code");
    }

    @Test
    @DisplayName("5.4: a save the hook refuses is one 422, writes nothing and publishes no event")
    void aRefusedSaveLeavesNoTrace() throws Exception {
        long before = count();
        int events = EntityRuntimeFixture.EVENTS.size();
        MockHttpServletResponse refused = ownerSession.send(post(ITEMS), Map.of("title", EntityRuntimeFixture.REFUSED));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(errors(refused)).containsExactly("title:refused");
        assertThat(count()).isEqualTo(before);
        assertThat(EntityRuntimeFixture.EVENTS).hasSize(events);
    }

    @Test
    @DisplayName("5.4: the problems of the body, the fields and the rules answer one 422 together")
    void everyProblemAnswersOne422() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "x".repeat(51));
        body.put("startsOn", "2026-10-10");
        body.put("endsOn", "2026-10-01");
        body.put("createdBy", 1);
        body.put("starred", List.of(true));
        MockHttpServletResponse refused = ownerSession.send(post(ITEMS), body);
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(errors(refused))
                .contains("title:too_long", "endsOn:before_start", "createdBy:unknown_field", "starred:invalid");
    }

    @Test
    @DisplayName("5.4: a reference to another person's note is 422 not_found, to an archived one 422 archived")
    void referencesAreCheckedInTheTargetsScope() throws Exception {
        long mine = note(ownerSession, "linked note");
        long theirs = note(otherSession, "their note");
        long archived = note(ownerSession, "archived note");
        MockHttpServletResponse archiving = ownerSession.send(
                put(NOTES + "/" + archived + "/archived").header("If-Match", "\"1\""), Map.of("archived", true));
        assertThat(archiving.getStatus()).as(archiving.getContentAsString()).isEqualTo(200);

        MockHttpServletResponse foreign = ownerSession.send(post(ITEMS), Map.of("title", "a", "noteId", theirs));
        assertThat(foreign.getStatus()).isEqualTo(422);
        assertThat(errors(foreign)).containsExactly("noteId:not_found");
        MockHttpServletResponse missing =
                ownerSession.send(post(ITEMS), Map.of("title", "a", "noteId", 9_000_000_000L));
        assertThat(errors(missing)).containsExactly("noteId:not_found");
        assertThat(errors(ownerSession.send(post(ITEMS), Map.of("title", "a", "noteId", archived))))
                .containsExactly("noteId:archived");

        MockHttpServletResponse linked = ownerSession.send(post(ITEMS), Map.of("title", "linked", "noteId", mine));
        assertThat(linked.getStatus()).as(linked.getContentAsString()).isEqualTo(201);
        long id = number(TestSession.object(linked).get("id"));
        // The note is archived after it was linked: the unchanged old value is kept on a save of something else.
        assertThat(ownerSession
                        .send(
                                put(NOTES + "/" + mine + "/archived").header("If-Match", "\"1\""),
                                Map.of("archived", true))
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse kept = ownerSession.send(
                patch(ITEMS + "/" + id).header("If-Match", "\"1\""), Map.of("title", "still linked", "noteId", mine));
        assertThat(kept.getStatus()).as(kept.getContentAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName(
            "5.4: an action names its revision, runs its handler, raises the revision and is audited with its code")
    void anActionRunsItsHandler() throws Exception {
        long id = item(ownerSession, "to star");
        String action = ITEMS + "/" + id + "/actions/star";
        assertThat(ownerSession.send(post(action)).getStatus()).isEqualTo(428);
        TestSession viewing = TestSession.signIn(wac, viewer.login());
        assertThat(viewing.send(post(action).header("If-Match", "\"1\"")).getStatus())
                .isEqualTo(403);
        assertThat(ownerSession
                        .send(post(ITEMS + "/" + id + "/actions/nope").header("If-Match", "\"1\""))
                        .getStatus())
                .isEqualTo(404);

        MockHttpServletResponse starred = ownerSession.send(post(action).header("If-Match", "\"1\""));
        assertThat(starred.getStatus()).as(starred.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(starred)).containsEntry("starred", true).containsEntry("revision", 2);
        assertThat(events(id).getLast().type()).isEqualTo(EntityEventType.ACTION);
        assertThat(events(id).getLast().action()).isEqualTo("star");
        String row = jdbc.sql("""
                        select new_row::text from audit_log
                        where table_name = :table and row_pk = :id and event = 'U'
                        order by id desc limit 1
                        """)
                .param("table", EntityRuntimeFixture.TABLE)
                .param("id", String.valueOf(id))
                .query(String.class)
                .single();
        assertThat(row).contains("\"_action\": \"star\"").contains("\"starred\": true");

        MockHttpServletResponse again = ownerSession.send(post(action).header("If-Match", "\"2\""));
        assertThat(again.getStatus()).isEqualTo(422);
        assertThat(errors(again)).containsExactly(":already_starred");
        // The record's own server-written fields stay the server's: a save may not write them.
        assertThat(errors(ownerSession.send(
                        patch(ITEMS + "/" + id).header("If-Match", "\"2\""), Map.of("starred", false))))
                .containsExactly("starred:readonly");
    }

    @Test
    @DisplayName("5.4: an afterCommit hook that fails is logged; the committed change and its answer stand")
    void aFailingAfterCommitDoesNotChangeTheAnswer() throws Exception {
        MockHttpServletResponse created =
                ownerSession.send(post(ITEMS), Map.of("title", EntityRuntimeFixture.FAILS_AFTER_COMMIT));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        long id = number(TestSession.object(created).get("id"));
        assertThat(EntityRuntimeFixture.COMMITTED).contains(id);
        assertThat(ownerSession.send(get(ITEMS + "/" + id)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADR-0032, 12: a filter or a search cannot reach records outside the scope")
    void filtersDoNotBypassTheScope() throws Exception {
        String tag = "fb" + UUID.randomUUID().toString().substring(0, 8);
        long theirs = note(otherSession, tag + " secret");
        MockHttpServletResponse byOwner = ownerSession.send(
                get(NOTES).param("filter", "[{\"field\":\"createdBy\",\"op\":\"eq\",\"value\":" + other.id() + "}]"));
        assertThat(byOwner.getStatus())
                .as("a filter on a field the list does not offer")
                .isEqualTo(422);
        MockHttpServletResponse searched = ownerSession.send(get(NOTES).param("q", tag));
        assertThat(searched.getStatus()).isEqualTo(200);
        assertThat((List<?>) TestSession.object(searched).get("items")).isEmpty();
        MockHttpServletResponse byId = ownerSession.send(
                get(NOTES).param("q", tag).param("filter", "[{\"field\":\"archived\",\"op\":\"eq\",\"value\":true}]"));
        assertThat((List<?>) TestSession.object(byId).get("items")).isEmpty();
        assertThat(otherSession.send(get(NOTES + "/" + theirs)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADR-0032, 6.3, step 1: an entity of a switched-off module answers as an unknown one")
    void aSwitchedOffModuleHidesItsEntity() throws Exception {
        modules.toggleModuleStatus("notes", false);
        try {
            MockHttpServletResponse hidden = ownerSession.send(get(NOTES));
            assertThat(hidden.getStatus()).isEqualTo(404);
            assertThat(TestSession.object(hidden)).containsEntry("messageKey", "error.common.entity_not_found");
            assertThat(ownerSession.send(get("/api/v1/entities/no.such")).getStatus())
                    .isEqualTo(404);
        } finally {
            modules.toggleModuleStatus("notes", true);
        }
        assertThat(ownerSession.send(get(NOTES)).getStatus()).isEqualTo(200);
    }

    private List<com.smartup24.cms.instance.common.entity.event.EntityChanged> events(long id) {
        return EntityRuntimeFixture.EVENTS.stream()
                .filter(event -> event.id() == id)
                .toList();
    }

    private long item(TestSession session, String title) throws Exception {
        MockHttpServletResponse created = session.send(post(ITEMS), Map.of("title", title));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return number(TestSession.object(created).get("id"));
    }

    private static long note(TestSession session, String title) throws Exception {
        MockHttpServletResponse created = session.send(post(NOTES), Map.of("title", title));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return number(TestSession.object(created).get("id"));
    }

    private long count() {
        Long rows = jdbc.sql("select count(*) from " + EntityRuntimeFixture.TABLE)
                .query(Long.class)
                .single();
        return rows == null ? 0 : rows;
    }

    private static List<String> errors(MockHttpServletResponse response) throws Exception {
        Object errors = TestSession.object(response).get("errors");
        if (!(errors instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> ((Map<?, ?>) item).get("field") + ":" + ((Map<?, ?>) item).get("code"))
                .toList();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
