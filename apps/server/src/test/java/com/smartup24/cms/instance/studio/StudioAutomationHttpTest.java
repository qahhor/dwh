package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.id;
import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.line;
import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.order;
import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.transition;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.studio.automation.AutomationScheduler;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
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
 * ADR-0046 over the server alone, on the reference order of ADR-0043 with a manager, a responsible person and a review
 * flag: a discount above 20 on save marks the order for review; an order sent for approval tells its manager; an
 * approved order creates a shipping task for its responsible person with the order's number. An automation is never
 * triggered by its own change; it runs with its author's rights, checked when it is saved and at every run; a dry run
 * changes nothing; a scheduled automation run now looks at the waiting orders; a new version of the form may not break
 * a switched-on automation.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioAutomationHttpTest extends EmbeddedPostgresTest {
    private static final String API = "/api/v1/automations";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private AutomationScheduler scheduler;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private TestSession publisher;
    private String orders;
    private String tasks;
    private Map<String, Object> declaration;
    private long client;
    private TestUser writerUser;
    private TestUser managerUser;
    private TestUser responsibleUser;
    private TestUser adminUser;
    private TestSession writer;
    private TestSession manager;
    private TestSession admin;
    private TestSession auditor;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long unit = users.unit("auto-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "md.settings", Set.of("update"),
                                        "studio.lists", Set.of("view", "manage")),
                                unit)
                        .login());
        String products = "aproducts_" + tag;
        forms.list(publisher, products, List.of("tea", "coffee"));
        String clients = forms.activate(publisher, forms.clients("aclients_" + tag));
        tasks = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "aships_" + tag,
                                "Задачи на отгрузку",
                                "all",
                                List.of(
                                        field("ordernum", "text", true, Map.of("maxLength", 100)),
                                        field("assignee", "user", false, Map.of()),
                                        field("note", "text", false, Map.of("maxLength", 200)))),
                        "ordernum",
                        null));
        declaration = order("aorders_" + tag, clients, products);
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) declaration.get("fields");
        fields.add(field("manager", "user", false, Map.of()));
        fields.add(field("responsible", "user", false, Map.of()));
        fields.add(field("review", "bool", false, Map.of()));
        orders = forms.activate(publisher, declaration);

        writerUser = users.withRights(
                Map.of(
                        orders,
                        Set.of("view", "create", "update", "submit"),
                        clients,
                        Set.of("view", "create"),
                        "md.users",
                        Set.of("view")),
                unit);
        managerUser = users.withRights(Map.of(orders, Set.of("view", "approve", "reject")), unit);
        responsibleUser = users.withRights(Map.of(orders, Set.of("view"), tasks, Set.of("view")), unit);
        adminUser = users.withRights(
                Map.of(
                        "studio.automations",
                        Set.of("view", "manage"),
                        orders,
                        Set.of("view", "create", "update", "submit", "approve"),
                        tasks,
                        Set.of("view", "create"),
                        "md.users",
                        Set.of("view")),
                unit);
        writer = TestSession.signIn(context, writerUser.login());
        manager = TestSession.signIn(context, managerUser.login());
        admin = TestSession.signIn(context, adminUser.login());
        auditor = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.automations", Set.of("view")), unit)
                        .login());
        client = forms.create(writer, clients, Map.of("name", "Клиент", "code", "c1"));
    }

    @Test
    void ordersAreFlaggedNotifiedAndShippedByTheirAutomations() throws Exception {
        long review = automation(admin, saved("Скидка больше 20", "discount > 20", setReview()));
        long pending = automation(admin, onTransition("Уведомить менеджера", List.of("pending"), notifyManager()));
        long shipping = automation(admin, onTransition("Задача на отгрузку", List.of("approved"), shippingTask()));

        Map<String, Object> created = newOrder(Map.of("discount", "25"));
        long order = id(created);
        assertThat(jobs.runQueued()).isGreaterThanOrEqualTo(1);
        Map<String, Object> flagged = read(writer, order);
        assertThat(flagged.get("review")).isEqualTo(true);
        String history =
                writer.send(get("/api/v1/history/" + orders + "/" + order)).getContentAsString();
        assertThat(history).contains("Скидка больше 20").contains("ui.history.automation");

        long revision = ((Number) flagged.get("revision")).longValue();
        MockHttpServletResponse submitted = transition(writer, orders, order, "submit", revision, Map.of());
        assertThat(submitted.getStatus()).as(submitted.getContentAsString()).isEqualTo(200);
        jobs.runQueued();
        String docNumber = String.valueOf(created.get("docNumber"));
        assertThat(jdbc.sql("select count(*) from ms_notifications where user_id = :user and title like :title"
                                + " and source_code like 'automation:%'")
                        .param("user", managerUser.id())
                        .param("title", "%" + docNumber + "%")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        MockHttpServletResponse approved = transition(manager, orders, order, "approve", revision + 1, Map.of());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        jobs.runQueued();
        List<Map<String, Object>> shipped = rows(admin.send(get("/api/v1/entities/" + tasks)));
        assertThat(shipped).hasSize(1);
        assertThat(shipped.getFirst().get("ordernum")).isEqualTo(docNumber);
        assertThat(((Number) shipped.getFirst().get("assignee")).longValue()).isEqualTo(responsibleUser.id());

        assertThat(runs(review)).extracting(run -> run.get("status")).containsExactly("done");
        assertThat(runs(pending)).extracting(run -> run.get("status")).containsExactly("done");
        assertThat(runs(shipping)).extracting(run -> run.get("status")).containsExactly("done");
        Map<String, Object> listed = TestSession.object(admin.send(get(API).param("form", orders)));
        assertThat((List<?>) listed.get("items")).hasSize(3);
    }

    @Test
    void anAutomationIsNotTriggeredByItsOwnChangeAndAConditionHolds() throws Exception {
        long flag = automation(admin, saved("Всегда помечать", null, setReview()));
        long order = id(newOrder(Map.of("discount", "5")));
        jobs.runQueued();
        jobs.runQueued();
        assertThat(read(writer, order).get("review")).isEqualTo(true);
        assertThat(runs(flag)).hasSize(1);

        long strict =
                automation(admin, saved("Только большие скидки", "discount > 20 and changed.discount", setReview()));
        Map<String, Object> current = read(writer, order);
        MockHttpServletResponse saved = writer.send(
                patch("/api/v1/entities/" + orders + "/" + order)
                        .header("If-Match", "\"" + current.get("revision") + "\""),
                Map.of("discount", "10"));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        jobs.runQueued();
        assertThat(runs(strict))
                .isNotEmpty()
                .allSatisfy(run -> assertThat(run.get("reason")).isEqualTo("condition"));
    }

    @Test
    void rightsAreCheckedWhenSavedAndAtEveryRun() throws Exception {
        assertThat(writer.send(post(API), saved("Чужая", null, setReview())).getStatus())
                .isEqualTo(403);
        TestUser weak = users.withRights(
                Map.of("studio.automations", Set.of("view", "manage"), orders, Set.of("view")), writerUser.unit());
        MockHttpServletResponse refused =
                TestSession.signIn(context, weak.login()).send(post(API), saved("Без права", null, setReview()));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(403);

        long flag = automation(admin, saved("Помечать", null, setReview()));
        jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", adminUser.id())
                .update();
        long order = id(newOrder(Map.of("discount", "1")));
        jobs.runQueued();
        assertThat(read(writer, order).get("review")).isNotEqualTo(true);
        Map<String, Object> run = runs(flag).getFirst();
        assertThat(run.get("status")).isEqualTo("failed");
        assertThat(run.get("reason")).isEqualTo("author_inactive");
    }

    @Test
    void aDryRunChangesNothingAndAScheduledAutomationLooksAtTheWaitingOrders() throws Exception {
        long order = id(newOrder(Map.of("discount", "30")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordId", order);
        body.put("automation", saved("Проба", "discount > 20", setReview()));
        MockHttpServletResponse dry = admin.send(post(API + "/dry-run"), body, null);
        assertThat(dry.getStatus()).as(dry.getContentAsString()).isEqualTo(200);
        Map<String, Object> outcome = TestSession.object(dry);
        assertThat(outcome.get("conditionHolds")).isEqualTo(true);
        assertThat(dry.getContentAsString()).contains("would_apply").contains("review");
        assertThat(read(writer, order).get("review")).isNotEqualTo(true);

        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put("form", orders);
        reminder.put("name", "Напоминание");
        reminder.put("timeZone", "Asia/Tashkent");
        reminder.put("trigger", Map.of("kind", "scheduled", "schedule", Map.of("frequency", "daily", "time", "09:00")));
        reminder.put("condition", "docStatus = \"draft\" and daysBetween(dateOf(modifiedAt), today()) >= 0");
        reminder.put("actions", List.of(notifyManager()));
        long scheduled = automation(admin, reminder);
        MockHttpServletResponse now = admin.send(post(API + "/" + scheduled + "/runs"), Map.of(), null);
        assertThat(now.getStatus()).as(now.getContentAsString()).isEqualTo(202);
        assertThat(((Number) TestSession.object(now).get("queued")).intValue()).isEqualTo(1);
        jobs.runQueued();
        assertThat(runs(scheduled)).extracting(run -> run.get("status")).containsExactly("done");
        assertThat(TestSession.object(auditor.send(get(API + "/" + scheduled))).get("nextRunAt"))
                .isNotNull();
        assertThat(scheduler.tick()).isNotNegative();
    }

    @Test
    void aNewVersionMayNotHideAFieldASwitchedOnAutomationWrites() throws Exception {
        long flag = automation(admin, saved("Помечать", null, setReview()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) declaration.get("fields");
        fields.getLast().put("hidden", true);
        declaration.put("formatVersion", 6);
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse refused = publisher.send(
                post("/api/v1/studio/forms/" + orders + "/versions").header("If-Match", "\"1\""),
                TestSession.object(prepared),
                null);
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("error.studio.automation_incompatible");

        Map<String, Object> stored = TestSession.object(admin.send(get(API + "/" + flag)));
        Map<String, Object> paused = saved("Помечать", null, setReview());
        paused.put("active", false);
        MockHttpServletResponse changed =
                admin.send(put(API + "/" + flag).header("If-Match", "\"" + stored.get("revision") + "\""), paused);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(admin.send(put(API + "/" + flag), paused).getStatus()).isEqualTo(428);
        assertThat(admin.send(delete(API + "/" + flag).header("If-Match", "\"1\""))
                        .getStatus())
                .isEqualTo(409);
    }

    private Map<String, Object> newOrder(Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("manager", managerUser.id());
        body.put("responsible", responsibleUser.id());
        body.put("items", List.of(line("tea", "2", "10", null)));
        body.putAll(extra);
        MockHttpServletResponse created = writer.send(
                post("/api/v1/entities/" + orders).header("X-Time-Zone", StudioDocumentFixtures.ZONE), body);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return TestSession.object(created);
    }

    private Map<String, Object> saved(String name, String condition, Map<String, Object> action) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("form", orders);
        request.put("name", name);
        request.put("trigger", Map.of("kind", "saved"));
        if (condition != null) request.put("condition", condition);
        request.put("actions", List.of(action));
        return request;
    }

    private Map<String, Object> onTransition(String name, List<String> to, Map<String, Object> action) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("form", orders);
        request.put("name", name);
        request.put("trigger", Map.of("kind", "transition", "to", to));
        request.put("actions", List.of(action));
        return request;
    }

    private static Map<String, Object> setReview() {
        return Map.of("type", "set_field", "values", List.of(Map.of("field", "review", "formula", "true")));
    }

    private static Map<String, Object> notifyManager() {
        return Map.of(
                "type", "notify",
                "channels", List.of("in_app"),
                "recipients", Map.of("fields", List.of("manager")),
                "title", Map.of("ru", "Заказ {docNumber} на согласовании"),
                "body", Map.of("ru", "Сумма {total}, статус {docStatus}"));
    }

    private Map<String, Object> shippingTask() {
        return Map.of(
                "type",
                "create_record",
                "form",
                tasks,
                "values",
                List.of(
                        Map.of("field", "ordernum", "formula", "docNumber"),
                        Map.of("field", "assignee", "formula", "responsible"),
                        Map.of("field", "note", "formula", "\"Отгрузить\"")));
    }

    private long automation(TestSession session, Map<String, Object> request) throws Exception {
        MockHttpServletResponse created = session.send(post(API), request);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) TestSession.object(created).get("id")).longValue();
    }

    private Map<String, Object> read(TestSession session, long order) throws Exception {
        return TestSession.object(session.send(get("/api/v1/entities/" + orders + "/" + order)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runs(long automation) throws Exception {
        MockHttpServletResponse page = auditor.send(get(API + "/" + automation + "/runs"));
        assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
        return (List<Map<String, Object>>) TestSession.object(page).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(MockHttpServletResponse page) throws Exception {
        assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
        return (List<Map<String, Object>>) TestSession.object(page).get("items");
    }
}
