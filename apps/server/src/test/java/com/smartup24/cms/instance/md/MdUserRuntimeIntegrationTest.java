package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.kauth.service.KauthInvitationIssued;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.context.WebApplicationContext;

/**
 * The user accounts on the general runtime (ADR-0032, 8; plan 10/10, item 5.6) beyond the entity contract: a new user is
 * invited and sets the first password under the password policy; the login, the e-mail and the phone of an active user
 * are each one account's; the state, the second factor, the forced password change, the roles and the password are
 * never written by a change (mass assignment, ADR-0032, 12); the password hash leaves the server in no answer, list,
 * export, history or webhook; blocking and anonymisation take every access away, and never touch the system
 * administrator.
 */
@RecordApplicationEvents
class MdUserRuntimeIntegrationTest extends EmbeddedPostgresTest {

    private static final String USERS = "/api/v1/entities/md.users";

    /** A password made up for this test only; the policy is 8 to 20 characters. */
    private static final String NEW_PASSWORD = "Invited-Pass-2026"; // gitleaks:allow -- synthetic test value

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private ApplicationEvents events;

    private TestUser actor;
    private TestSession admin;
    private long unit;
    private String tag;

    @BeforeEach
    void signIn() throws Exception {
        TestUsers users = TestUsers.of(wac);
        unit = users.unit("users-runtime");
        actor = users.withRights(
                Map.of(
                        MdUserEntity.CODE,
                        Set.of("view", "create", "update", "block", "unblock", "delete"),
                        "md.assignments",
                        Set.of("view"),
                        "md.org_units",
                        Set.of("assign")),
                unit);
        admin = TestSession.signIn(wac, actor.login());
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("5.6: a new user is invited, has no password until the link sets one, and then signs in")
    void aNewUserIsInvitedAndSetsTheFirstPassword() throws Exception {
        MockHttpServletResponse created = admin.send(
                post(USERS),
                Map.of("name", "Invited " + tag, "login", "Inv." + tag, "email", "Inv." + tag + "@Example.TEST"));

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        Map<String, Object> record = TestSession.object(created);
        long id = ((Number) record.get("id")).longValue();
        assertThat(record)
                .containsEntry("login", "inv." + tag)
                .containsEntry("email", "inv." + tag + "@example.test")
                .containsEntry("state", "A")
                .containsEntry("orgUnitId", (int) unit)
                .doesNotContainKeys("password", "passwordHash", "authVersion", "avatarFileId");
        assertThat(jdbc.sql("select password_hash from md_users where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .optional())
                .as("no password before the invitation is used")
                .isEmpty();
        assertThat(jdbc.sql("""
                        select r.pcode from md_user_roles ur join md_roles r on r.id = ur.role_id
                        where ur.user_id = :id
                        """).param("id", id).query(String.class).list())
                .as("the default role")
                .containsExactly("user");

        KauthInvitationIssued invitation = events.stream(KauthInvitationIssued.class)
                .filter(event -> event.channel().userId() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no invitation of user " + id));
        assertThat(invitation.login()).isEqualTo("inv." + tag);
        MockHttpServletResponse weak = admin.send(
                post("/api/v1/auth/password-reset/confirm"),
                Map.of("token", invitation.token(), "newPassword", "short"),
                null);
        assertThat(weak.getStatus())
                .as("the password policy holds for an invitation")
                .isEqualTo(422);
        MockHttpServletResponse confirmed = admin.send(
                post("/api/v1/auth/password-reset/confirm"),
                Map.of("token", invitation.token(), "newPassword", NEW_PASSWORD),
                null);
        assertThat(confirmed.getStatus()).as(confirmed.getContentAsString()).isEqualTo(204);

        TestSession invited = TestSession.signIn(wac, "inv." + tag, NEW_PASSWORD);
        assertThat(invited.send(get("/api/v1/auth/me")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("5.6: a login, an e-mail and the phone of an active user are each one account's: 422 on the field")
    void loginEmailAndPhoneAreUnique() throws Exception {
        Map<String, Object> first = Map.of(
                "name",
                "First " + tag,
                "login",
                "u" + tag,
                "email",
                "u" + tag + "@example.test",
                "phone",
                "+998901112233");
        assertThat(admin.send(post(USERS), first).getStatus()).isIn(201, 422);

        MockHttpServletResponse login = admin.send(
                post(USERS), Map.of("name", "Second", "login", "U" + tag, "email", "other" + tag + "@example.test"));
        assertThat(fields(login)).contains("login:already_exists");
        MockHttpServletResponse email = admin.send(
                post(USERS), Map.of("name", "Third", "login", "v" + tag, "email", "U" + tag + "@example.test"));
        assertThat(fields(email)).contains("email:already_exists");
        MockHttpServletResponse phone = admin.send(
                post(USERS),
                Map.of(
                        "name",
                        "Fourth",
                        "login",
                        "w" + tag,
                        "email",
                        "w" + tag + "@example.test",
                        "phone",
                        "+998901112233"));
        assertThat(fields(phone)).contains("phone:already_exists");
    }

    @Test
    @DisplayName("5.6: state, the second factor, the forced change, roles, login and a password are never patched")
    void massAssignmentIsRefused() throws Exception {
        long id = create("mass");
        long revision = revision(id);
        Map<String, String> refused = Map.of(
                "state", "state:readonly",
                "is2faEnabled", "is2faEnabled:readonly",
                "credentialChangeRequired", "credentialChangeRequired:readonly",
                "login", "login:readonly",
                "roleIds", "roleIds:unknown_field",
                "passwordHash", "passwordHash:unknown_field",
                "password", "password:unknown_field",
                "authVersion", "authVersion:unknown_field");
        Map<String, Object> values = Map.of(
                "state",
                "P",
                "is2faEnabled",
                true,
                "credentialChangeRequired",
                true,
                "login",
                "renamed" + tag,
                "roleIds",
                List.of(1),
                "passwordHash",
                "x",
                "password",
                "Another-Pass-2026",
                "authVersion",
                7);
        for (Map.Entry<String, String> field : refused.entrySet()) {
            MockHttpServletResponse answer = admin.send(
                    patch(USERS + "/" + id).header("If-Match", "\"" + revision + "\""),
                    Map.of(field.getKey(), values.get(field.getKey())));
            assertThat(answer.getStatus()).as(field.getKey()).isEqualTo(422);
            assertThat(fields(answer)).as(field.getKey()).contains(field.getValue());
        }
        assertThat(revision(id)).as("nothing was written").isEqualTo(revision);
    }

    @Test
    @DisplayName("5.6: the password hash is in no record, list, export, history or webhook payload")
    void thePasswordHashNeverLeaves() throws Exception {
        long id = create("secret");
        String hash = "$argon2id$v=19$m=65536,t=3,p=1$" + tag;
        jdbc.sql("update md_users set password_hash = :hash where id = :id")
                .param("hash", hash)
                .param("id", id)
                .update();
        long subscription = subscribe();
        try {
            MockHttpServletResponse changed = admin.send(
                    patch(USERS + "/" + id).header("If-Match", "\"" + revision(id) + "\""),
                    Map.of("name", "Renamed " + tag));
            assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);

            for (String answer : List.of(
                    changed.getContentAsString(StandardCharsets.UTF_8),
                    body(admin.send(get(USERS + "/" + id))),
                    body(admin.send(get(USERS).param("limit", "200"))),
                    body(admin.send(get("/api/v1/history/md.users/" + id))),
                    exported(),
                    String.join(
                            "\n",
                            jdbc.sql("select payload::text from kwh_outbox where subscription_id = :s")
                                    .param("s", subscription)
                                    .query(String.class)
                                    .list()))) {
                assertThat(answer).doesNotContain(hash).doesNotContainIgnoringCase("passwordHash");
            }
        } finally {
            jdbc.sql("delete from kwh_subscriptions where id = :id")
                    .param("id", subscription)
                    .update();
        }
    }

    @Test
    @DisplayName(
            "5.6: blocking takes every access away at once; the system administrator is never blocked or anonymised")
    void blockingRevokesAccessAndTheAdministratorIsProtected() throws Exception {
        TestUser target = TestUsers.of(wac).withRights(Map.of(), unit);
        TestSession targetSession = TestSession.signIn(wac, target.login());
        long before = authVersion(target.id());

        MockHttpServletResponse blocked = action(target.id(), MdUserEntity.BLOCK);
        assertThat(blocked.getStatus()).as(blocked.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(blocked)).containsEntry("state", "P");
        assertThat(authVersion(target.id()))
                .as("a new authentication generation")
                .isGreaterThan(before);
        assertThat(targetSession.send(get("/api/v1/auth/me")).getStatus())
                .as("the session made before the block")
                .isEqualTo(401);

        MockHttpServletResponse anonymized = action(target.id(), MdUserEntity.ANONYMIZE);
        assertThat(anonymized.getStatus()).as(anonymized.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(anonymized))
                .containsEntry("name", "Deleted User " + target.id())
                .containsEntry("login", "deleted_" + target.id())
                .doesNotContainKey("phone");
        assertThat(jdbc.sql("select password_hash from md_users where id = :id")
                        .param("id", target.id())
                        .query(String.class)
                        .single())
                .isEqualTo("ANONYMIZED");

        // Everyone in sight: the administrator account lies in no unit, so the actor's role sees all.
        long role = jdbc.sql("select role_id from md_user_roles where user_id = :id and role_id <> ("
                        + "select id from md_roles where pcode = 'user')")
                .param("id", actor.id())
                .query(Long.class)
                .list()
                .getFirst();
        scopeRepository.setRoleRule(role, MdScopeService.RULE_ALL);
        scopes.recalculateFor(actor.id());
        long system = jdbc.sql("select id from md_users where login = 'admin'")
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                                insert into md_users (name, login, email, state, org_unit_id)
                                values ('TEST administrator', 'admin', 'admin-' || :tag || '@test.local', 'A', :unit)
                                returning id
                                """)
                        .param("tag", tag)
                        .param("unit", unit)
                        .query(Long.class)
                        .single());
        for (String action : List.of(MdUserEntity.BLOCK, MdUserEntity.ANONYMIZE)) {
            MockHttpServletResponse refused = action(system, action);
            assertThat(refused.getStatus())
                    .as(action + ": " + refused.getContentAsString())
                    .isEqualTo(403);
            assertThat(TestSession.object(refused)).containsEntry("code", "superadmin_immutable");
        }
        assertThat(jdbc.sql("select state from md_users where id = :id")
                        .param("id", system)
                        .query(String.class)
                        .single())
                .isEqualTo("A");
    }

    private long create(String name) throws Exception {
        MockHttpServletResponse created = admin.send(
                post(USERS),
                Map.of("name", name + " " + tag, "login", name + tag, "email", name + tag + "@example.test"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) TestSession.object(created).get("id")).longValue();
    }

    private MockHttpServletResponse action(long id, String action) throws Exception {
        return admin.send(post(USERS + "/" + id + "/actions/" + action).header("If-Match", "\"" + revision(id) + "\""));
    }

    private long revision(long id) {
        return jdbc.sql("select revision from md_users where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long authVersion(long id) {
        return jdbc.sql("select auth_version from md_users where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    /** Every cell of an export of the user list with every column the viewer may see. */
    private String exported() throws Exception {
        List<String> columns = ((List<?>) TestSession.object(admin.send(get("/api/v1/query-meta/" + MdUserEntity.CODE)))
                        .get("fields"))
                .stream()
                        .map(field -> String.valueOf(((Map<?, ?>) field).get("key")))
                        .toList();
        jdbc.sql("delete from fnd_job_queue").update();
        MockHttpServletResponse asked = admin.send(
                post("/api/v1/exports"), Map.of("list", MdUserEntity.CODE, "lang", "en", "columns", columns));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        Object id = TestSession.object(asked).get("id");
        wac.getBean(JobRunner.class).runQueued();
        MockHttpServletResponse file = admin.send(get("/api/v1/exports/" + id + "/file"));
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        StringBuilder cells = new StringBuilder(String.join(",", columns)).append('\n');
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            for (Row row : workbook.getFirstSheet().read()) {
                row.stream()
                        .forEach(cell ->
                                cells.append(cell == null ? "" : cell.getText()).append(','));
                cells.append('\n');
            }
        }
        return cells.toString();
    }

    private long subscribe() {
        long creator = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        return wac.getBean(WebhookSubscriptionRepository.class)
                .create(
                        "users " + tag,
                        "https://hooks.example.test/users",
                        "users-secret",
                        List.of(MdUserEntity.CODE + ".updated"),
                        creator)
                .id();
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> fields(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        Object errors = TestSession.object(response).get("errors");
        return ((List<?>) errors)
                .stream()
                        .map(item -> ((Map<?, ?>) item).get("field") + ":" + ((Map<?, ?>) item).get("code"))
                        .toList();
    }
}
