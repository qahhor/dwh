package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

/**
 * The scope of a webhook subscription over a live form scoped to the org unit (ADR-0041, owner decisions of
 * 2026-10-18): a subscription scoped to its creator hears of the records its creator may read — created, changed and
 * deleted in the creator's unit — and nothing of another unit's records, while a subscription for all records hears
 * of every one; a creator who lost the right or was blocked makes the scoped subscription hear nothing; the scope is
 * changed through the API with If-Match and the change is audited.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioWebhookScopeHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long home;
    private long other;
    private String code;
    private TestUser creator;
    private TestSession admin;
    private TestSession neighbour;
    private TestSession stranger;
    private long scoped;
    private long everything;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        home = users.unit("hook-home-" + tag);
        other = users.unit("hook-other-" + tag);
        TestSession publisher = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.forms", Set.of("publish"), "md.settings", Set.of("update")), home)
                        .login());
        code = forms.activate(
                publisher,
                forms.plain(
                        "hooks_" + tag,
                        "Визиты",
                        "org_unit",
                        List.of(StudioFormFixtures.field("note", "text", false, Map.of("maxLength", 100)))));
        creator =
                users.withRights(Map.of("webhook.subscriptions", Set.of("view", "manage"), code, Set.of("view")), home);
        admin = TestSession.signIn(context, creator.login());
        Map<String, Set<String>> writer = Map.of(code, Set.of("view", "create", "update", "delete"));
        neighbour = TestSession.signIn(context, users.withRights(writer, home).login());
        stranger = TestSession.signIn(context, users.withRights(writer, other).login());
        List<String> events = List.of(code + ".created", code + ".updated", code + ".deleted");
        scoped = subscriptions
                .create("scoped " + tag, "https://hooks.example.test/s", "k1", events, "creator", creator.id())
                .id();
        everything = subscriptions
                .create("all " + tag, "https://hooks.example.test/a", "k2", events, "all", creator.id())
                .id();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from kwh_subscriptions where id in (:ids)")
                .param("ids", List.of(scoped, everything))
                .update();
    }

    @Test
    void aCreatorScopedSubscriptionHearsOnlyOfTheRecordsItsCreatorReads() throws Exception {
        long mine = forms.create(neighbour, code, Map.of("name", "Свой", "code", "own-" + tag));
        long theirs = forms.create(stranger, code, Map.of("name", "Чужой", "code", "far-" + tag));

        assertThat(recordsHeard(scoped, code + ".created")).containsExactly(mine);
        assertThat(recordsHeard(everything, code + ".created")).containsExactly(mine, theirs);

        MockHttpServletResponse changed = stranger.send(
                patch("/api/v1/entities/" + code + "/" + theirs).header("If-Match", "\"1\""), Map.of("note", "x"));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(recordsHeard(scoped, code + ".updated")).isEmpty();
        assertThat(recordsHeard(everything, code + ".updated")).containsExactly(theirs);

        for (var removal : Map.of(neighbour, mine, stranger, theirs).entrySet()) {
            MockHttpServletResponse deleted = removal.getKey()
                    .send(delete("/api/v1/entities/" + code + "/" + removal.getValue())
                            .header("If-Match", removal.getValue() == theirs ? "\"2\"" : "\"1\""));
            assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(204);
        }
        assertThat(recordsHeard(scoped, code + ".deleted")).containsExactly(mine);
        assertThat(recordsHeard(everything, code + ".deleted")).containsExactlyInAnyOrder(mine, theirs);
    }

    @Test
    void aCreatorWithoutTheRightOrBlockedMakesTheScopedSubscriptionHearNothing() throws Exception {
        jdbc.sql("delete from md_effective_permissions where user_id = :id and form_code = :form")
                .param("id", creator.id())
                .param("form", code)
                .update();
        long first = forms.create(neighbour, code, Map.of("name", "Первый", "code", "a-" + tag));
        assertThat(recordsHeard(scoped, code + ".created")).isEmpty();
        assertThat(recordsHeard(everything, code + ".created")).containsExactly(first);

        jdbc.sql("""
                        insert into md_effective_permissions (user_id, form_code, action)
                        values (:id, :form, 'view') on conflict do nothing""").param("id", creator.id()).param("form", code).update();
        long second = forms.create(neighbour, code, Map.of("name", "Второй", "code", "b-" + tag));
        assertThat(recordsHeard(scoped, code + ".created")).containsExactly(second);

        jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", creator.id())
                .update();
        forms.create(neighbour, code, Map.of("name", "Третий", "code", "c-" + tag));
        assertThat(recordsHeard(scoped, code + ".created")).containsExactly(second);
        assertThat(recordsHeard(everything, code + ".created")).hasSize(3);
    }

    @Test
    void theScopeIsChangedWithIfMatchAndAudited() throws Exception {
        MockHttpServletResponse invalid = admin.send(
                patch("/api/v1/webhooks/subscriptions/" + everything).header("If-Match", "\"1\""),
                Map.of("scope", "unit"));
        assertThat(invalid.getStatus()).isEqualTo(422);
        assertThat(invalid.getContentAsString()).contains("error.webhook.scope_invalid");

        MockHttpServletResponse unconditional =
                admin.send(patch("/api/v1/webhooks/subscriptions/" + everything), Map.of("scope", "creator"));
        assertThat(unconditional.getStatus()).isEqualTo(428);

        MockHttpServletResponse narrowed = admin.send(
                patch("/api/v1/webhooks/subscriptions/" + everything).header("If-Match", "\"1\""),
                Map.of("scope", "creator"));
        assertThat(narrowed.getStatus()).as(narrowed.getContentAsString()).isEqualTo(204);
        assertThat(narrowed.getHeader("ETag")).isEqualTo("\"2\"");

        List<Object> listed = TestSession.array(admin.send(get("/api/v1/webhooks/subscriptions")));
        assertThat(listed)
                .anySatisfy(item -> assertThat(String.valueOf(item))
                        .contains("id=" + everything + ",")
                        .contains("scope=creator"));
        String audited = jdbc.sql("""
                        select new_row::text from audit_log
                        where table_name = 'kwh_subscriptions' and row_pk = :pk and event = 'U'
                        order by changed_at desc limit 1""")
                .param("pk", String.valueOf(everything))
                .query(String.class)
                .single();
        assertThat(audited).contains("\"scope\": \"creator\"");

        long theirs = forms.create(stranger, code, Map.of("name", "Чужой", "code", "z-" + tag));
        assertThat(recordsHeard(everything, code + ".created")).doesNotContain(theirs);
    }

    /** The records the subscription's events of the type name, in the order they were written. */
    private List<Long> recordsHeard(long subscription, String type) throws Exception {
        return jdbc.sql("""
                        select (payload->>'recordId')::bigint from kwh_outbox
                        where subscription_id = :subscription and event_type = :type
                        order by id""")
                .param("subscription", subscription)
                .param("type", type)
                .query(Long.class)
                .list();
    }
}
