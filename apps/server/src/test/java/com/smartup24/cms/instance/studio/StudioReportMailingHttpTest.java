package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.report.mailing.MailingScheduler;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * ADR-0045 over the server alone: a mailing of a saved report of orders seen by org unit, with a required period
 * parameter fixed to the previous month, email and in-app, the XLSX attached, sent only when the report has rows. A send
 * now runs the report as each recipient — the owner of unit A sees two regions, the reader of unit B one, the reader of
 * unit C none and gets nothing; a blocked recipient is skipped. Pausing refuses a send to all but not a test to self;
 * the scheduler takes a due slot once; changes name their revision; the rights and the checks answer at the part.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioReportMailingHttpTest extends EmbeddedPostgresTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private MailingScheduler scheduler;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private String orders;
    private TestSession owner;
    private TestSession south;
    private TestUser ownerUser;
    private TestUser southUser;
    private TestUser eastUser;
    private TestUser strangerUser;
    private long viewId;

    @BeforeEach
    void setup() throws Exception {
        TestUsers users = TestUsers.of(context);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long unitA = users.unit("ma-" + tag);
        long unitB = users.unit("mb-" + tag);
        long unitC = users.unit("mc-" + tag);
        TestSession publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "studio.lists", Set.of("view", "manage"),
                                        "md.settings", Set.of("update")),
                                unitA)
                        .login());
        String regions = "mregions_" + tag;
        forms.list(publisher, regions, List.of("north", "south", "east"));
        orders = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "morders_" + tag,
                                "Заказы",
                                "org_unit",
                                List.of(
                                        field(
                                                "region",
                                                "ref",
                                                true,
                                                Map.of("target", Map.of("kind", "list", "code", regions))),
                                        field("orderdate", "date", true, Map.of()),
                                        field("total", "number", false, Map.of("scale", 2)))),
                        null,
                        null));
        Map<String, Set<String>> rights = Map.of(
                orders,
                Set.of("view", "create"),
                "md.profile",
                Set.of("view", "update"),
                "md.settings",
                Set.of("update"),
                "report.mailings",
                Set.of("own"));
        ownerUser = users.withRights(rights, unitA);
        southUser = users.withRights(rights, unitB);
        eastUser = users.withRights(rights, unitC);
        strangerUser = users.withRights(rights, unitB);
        owner = TestSession.signIn(context, ownerUser.login());
        south = TestSession.signIn(context, southUser.login());
        TestSession east = TestSession.signIn(context, eastUser.login());

        LocalDate today = LocalDate.now(ZONE);
        String lastMonth = today.withDayOfMonth(1).minusMonths(1).plusDays(9).toString();
        order(owner, "north", lastMonth, "100");
        order(owner, "east", lastMonth, "50");
        order(owner, "north", today.toString(), "999");
        order(south, "south", lastMonth, "700");
        order(east, "east", today.toString(), "5");

        Map<String, Object> state = Map.of(
                "groupBy", List.of(Map.of("field", "region")),
                "measures", List.of(Map.of("op", "sum", "field", "total")),
                "chart", "table",
                "totals", true,
                "parameters", List.of(Map.of("key", "period", "field", "orderdate", "required", true)));
        MockHttpServletResponse saved = owner.send(
                post("/api/v1/list-views/" + orders),
                Map.of("name", "Заказы по регионам " + tag, "kind", "report", "state", state, "isDefault", false),
                null);
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(201);
        viewId = ((Number) TestSession.object(saved).get("id")).longValue();
        List<Long> roles = jdbc.sql("select role_id from md_user_roles where user_id in (:ids)")
                .param("ids", List.of(southUser.id(), eastUser.id()))
                .query(Long.class)
                .list();
        MockHttpServletResponse shared = owner.send(
                put("/api/v1/list-views/" + orders + "/" + viewId + "/sharing").header("If-Match", "0"),
                Map.of("roleIds", roles),
                null);
        assertThat(shared.getStatus()).as(shared.getContentAsString()).isEqualTo(200);
        for (TestUser user : List.of(ownerUser, southUser, eastUser)) {
            jdbc.sql("""
                            insert into kauth_user_channels (user_id, channel, address, is_verified, created_at)
                            values (:user, 'email', :address, true, now())
                            """)
                    .param("user", user.id())
                    .param("address", user.login() + "@mail.test")
                    .update();
        }
    }

    private void order(TestSession writer, String region, String date, String total) throws Exception {
        forms.create(writer, orders, Map.of("region", region, "orderdate", date, "total", total));
    }

    private Map<String, Object> request(List<Long> recipients, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("viewId", viewId);
        body.put("name", "Итоги прошлого месяца");
        body.put("recipientIds", recipients);
        body.put("channels", List.of("email", "in_app"));
        body.put("attachXlsx", true);
        body.put("includeLink", true);
        body.put(
                "schedule",
                Map.of("frequency", "monthly", "time", "08:00", "timeZone", "Asia/Tashkent", "monthDay", 1));
        body.put("parameters", Map.of("period", Map.of("preset", "last_month")));
        body.put("condition", Map.of("kind", "has_rows"));
        body.put("active", true);
        body.putAll(overrides);
        return body;
    }

    private Map<String, Object> create(Map<String, Object> body) throws Exception {
        MockHttpServletResponse created = owner.send(post("/api/v1/report-mailings"), body, null);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        assertThat(created.getHeader("Location")).startsWith("/api/v1/report-mailings/");
        return TestSession.object(created);
    }

    private long run(long mailing, String scope, TestSession who) throws Exception {
        MockHttpServletResponse sent =
                who.send(post("/api/v1/report-mailings/" + mailing + "/runs"), Map.of("scope", scope), null);
        assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(201);
        jobs.runQueued();
        return ((Number) TestSession.object(sent).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> deliveries(long mailing, long runId) throws Exception {
        MockHttpServletResponse detail = owner.send(get("/api/v1/report-mailings/" + mailing + "/runs/" + runId));
        assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
        Map<String, Object> body = TestSession.object(detail);
        assertThat(((Map<String, Object>) body.get("run")).get("status")).isEqualTo("done");
        Map<String, Map<String, Object>> byTarget = new HashMap<>();
        for (Object item : (List<Object>) body.get("deliveries")) {
            Map<String, Object> delivery = (Map<String, Object>) item;
            byTarget.put(delivery.get("userId") + ":" + delivery.get("channel"), delivery);
        }
        return byTarget;
    }

    @Test
    void aSendNowRunsTheReportAsEachRecipientAndSkipsWhoHasNoRows() throws Exception {
        Map<String, Object> mailing = create(request(List.of(ownerUser.id(), southUser.id(), eastUser.id()), Map.of()));
        long id = ((Number) mailing.get("id")).longValue();
        assertThat(mailing.get("nextRunAt")).isNotNull();
        assertThat((List<?>) mailing.get("recipients")).hasSize(3);

        long runId = run(id, "all", owner);
        var got = deliveries(id, runId);
        assertThat(got.get(ownerUser.id() + ":email"))
                .containsEntry("status", "queued")
                .containsEntry("rows", 2);
        assertThat(got.get(ownerUser.id() + ":in_app"))
                .containsEntry("status", "sent")
                .containsEntry("rows", 2);
        assertThat(got.get(southUser.id() + ":email"))
                .containsEntry("status", "queued")
                .containsEntry("rows", 1);
        assertThat(got.get(eastUser.id() + ":email"))
                .containsEntry("status", "skipped")
                .containsEntry("reason", "condition");
        assertThat(got.get(eastUser.id() + ":in_app")).containsEntry("reason", "condition");

        List<Map<String, Object>> outbox = jdbc.sql("""
                        select recipient, payload::text as payload from ms_notification_outbox
                        where template_code = 'report_mailing' and recipient in (:to)
                        """)
                .param(
                        "to",
                        List.of(
                                ownerUser.login() + "@mail.test",
                                southUser.login() + "@mail.test",
                                eastUser.login() + "@mail.test"))
                .query((rs, row) ->
                        Map.<String, Object>of("to", rs.getString("recipient"), "payload", rs.getString("payload")))
                .list();
        assertThat(outbox)
                .extracting(row -> row.get("to"))
                .containsExactlyInAnyOrder(ownerUser.login() + "@mail.test", southUser.login() + "@mail.test");
        assertThat(outbox)
                .extracting(row -> (String) row.get("payload"))
                .anyMatch(payload -> payload.contains("Итоги прошлого месяца") && payload.contains("в отчёте: 2."))
                .anyMatch(payload -> payload.contains("в отчёте: 1."));
        String link = jdbc.sql("select form_link from ms_notifications where user_id = :user and source_code = :source")
                .param("user", southUser.id())
                .param("source", "report_mailing:" + id)
                .query(String.class)
                .single();
        assertThat(link).startsWith("/e/" + orders + "?mode=report&report=" + viewId + "&parameters=");
        assertThat(link).doesNotContain("token");

        MockHttpServletResponse runs = owner.send(get("/api/v1/report-mailings/" + id + "/runs"));
        assertThat(runs.getStatus()).isEqualTo(200);
        assertThat(runs.getContentAsString()).contains("\"kind\":\"manual\"", "\"sent\":4", "\"skipped\":2");

        jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", southUser.id())
                .update();
        var blocked = deliveries(id, run(id, "all", owner));
        assertThat(blocked.get(southUser.id() + ":email")).containsEntry("reason", "inactive");
        jdbc.sql("update md_users set state = 'A' where id = :id")
                .param("id", southUser.id())
                .update();
    }

    @Test
    void pausingStopsSendsToAllButNotATestToSelfAndTheSchedulerTakesASlotOnce() throws Exception {
        Map<String, Object> mailing = create(request(List.of(southUser.id()), Map.of()));
        long id = ((Number) mailing.get("id")).longValue();
        String etag = "\"" + mailing.get("revision") + "\"";

        jdbc.sql("update report_mailings set next_run_at = now() - interval '3 days' where id = :id")
                .param("id", id)
                .update();
        assertThat(scheduler.tick()).isGreaterThanOrEqualTo(1);
        assertThat(scheduler.tick()).isZero();
        assertThat(jdbc.sql("select count(*) from report_mailing_runs where mailing_id = :id and kind = 'scheduled'")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select next_run_at > now() from report_mailing_runs r join report_mailings m"
                                + " on m.id = r.mailing_id where m.id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        jobs.runQueued();

        MockHttpServletResponse paused = owner.send(
                put("/api/v1/report-mailings/" + id).header("If-Match", etag),
                request(List.of(southUser.id()), Map.of("active", false)),
                null);
        assertThat(paused.getStatus()).as(paused.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(paused).get("nextRunAt")).isNull();
        MockHttpServletResponse refused =
                owner.send(post("/api/v1/report-mailings/" + id + "/runs"), Map.of("scope", "all"), null);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getContentAsString()).contains("error.report.mailing_paused");
        assertThat(scheduler.tick()).isZero();

        var test = deliveries(id, run(id, "me", owner));
        assertThat(test).containsOnlyKeys(ownerUser.id() + ":email", ownerUser.id() + ":in_app");
        assertThat(test.get(ownerUser.id() + ":email")).containsEntry("status", "queued");

        MockHttpServletResponse stale = owner.send(
                put("/api/v1/report-mailings/" + id).header("If-Match", etag),
                request(List.of(southUser.id()), Map.of()),
                null);
        assertThat(stale.getStatus()).isEqualTo(409);
        MockHttpServletResponse missing =
                owner.send(put("/api/v1/report-mailings/" + id), request(List.of(southUser.id()), Map.of()), null);
        assertThat(missing.getStatus()).isEqualTo(428);
        String current = "\"" + TestSession.object(paused).get("revision") + "\"";
        assertThat(owner.send(delete("/api/v1/report-mailings/" + id).header("If-Match", current), Map.of(), null)
                        .getStatus())
                .isEqualTo(204);
        assertThat(owner.send(get("/api/v1/report-mailings/" + id)).getStatus()).isEqualTo(404);
    }

    @Test
    void theChecksAndTheRightsAnswerAtThePart() throws Exception {
        MockHttpServletResponse options = owner.send(get("/api/v1/report-mailings/options"));
        assertThat(options.getStatus()).isEqualTo(200);
        assertThat(options.getContentAsString()).contains("\"in_app\"", "\"email\"", "\"canManageAll\":false");

        MockHttpServletResponse noPeriod = owner.send(
                post("/api/v1/report-mailings"),
                request(List.of(southUser.id()), Map.of("parameters", Map.of())),
                null);
        assertThat(noPeriod.getStatus()).isEqualTo(422);
        assertThat(noPeriod.getContentAsString()).contains("parameters.period");

        Map<String, Object> wrong = request(
                List.of(southUser.id()),
                Map.of(
                        "channels", List.of("fax"),
                        "schedule", Map.of("frequency", "daily", "time", "25:00", "timeZone", "Asia/Tashkent"),
                        "condition", Map.of("kind", "threshold", "measure", 3, "op", "gt", "value", 10),
                        "attachXlsx", false,
                        "includeLink", false));
        MockHttpServletResponse invalid = owner.send(post("/api/v1/report-mailings"), wrong, null);
        assertThat(invalid.getStatus()).isEqualTo(422);
        assertThat(invalid.getContentAsString())
                .contains("\"channels\"", "schedule.time", "condition.measure", "\"attachXlsx\"");

        MockHttpServletResponse notOwner =
                south.send(post("/api/v1/report-mailings"), request(List.of(southUser.id()), Map.of()), null);
        assertThat(notOwner.getStatus()).isEqualTo(403);
        TestSession stranger = TestSession.signIn(context, strangerUser.login());
        MockHttpServletResponse unseen =
                stranger.send(post("/api/v1/report-mailings"), request(List.of(strangerUser.id()), Map.of()), null);
        assertThat(unseen.getStatus()).isEqualTo(404);
        assertThat(stranger.send(get("/api/v1/report-mailings").param("viewId", Long.toString(viewId)))
                        .getStatus())
                .isEqualTo(404);

        Map<String, Object> mailing = create(request(
                List.of(southUser.id()),
                Map.of("condition", Map.of("kind", "threshold", "measure", 0, "op", "gte", "value", 100))));
        assertThat(mailing.get("condition").toString()).contains("threshold");
        MockHttpServletResponse page =
                owner.send(get("/api/v1/report-mailings").param("viewId", Long.toString(viewId)));
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentAsString()).contains("\"name\":\"Итоги прошлого месяца\"");
        assertThat(south.send(get("/api/v1/report-mailings/" + mailing.get("id")))
                        .getStatus())
                .isEqualTo(403);
    }
}
