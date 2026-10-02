package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.common.security.ScopeFixture.Kind;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-0013, 2.5: every figure of the analytics dashboard, and the task export the dashboard offers, is computed only
 * over the records the viewer may see. Tasks
 * follow the task predicate, projects the project predicate, users the user predicate; a viewer with the rule ALL keeps
 * the figures of the whole installation.
 *
 * <p>The records: unit A holds the matrix viewer (rule UNITS), the insider, a SELF viewer and an ALL viewer; unit B
 * holds the outsider. The insider has one open task in a project of their own; the SELF viewer has one task without a
 * project; the outsider has a project with three tasks (one open, one done, one overdue) that no one in unit A takes
 * part in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsScopeIntegrationTest extends EmbeddedPostgresTest {

    private static final String ANALYTICS = "/api/v1/analytics";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdUserService users;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private MdRoleRepository roles;

    @Autowired
    private MfFileService files;

    private ScopeFixture fixture;
    private TestSession unitsViewer;
    private TestSession selfViewer;
    private TestSession allViewer;
    private long selfViewerId;
    private long insideProject;
    private long outsideProject;
    private List<Long> visibleTasks;
    private List<Long> hiddenTasks;

    @BeforeAll
    void setUp() throws Exception {
        fixture = new ScopeFixture(jdbc, users, scopes, scopeRepository, roles, files);
        TestUsers testUsers = new TestUsers(jdbc, users, scopes, scopeRepository, roles);
        unitsViewer = TestSession.signIn(wac, fixture.viewerLogin);
        TestUser self = withRule(testUsers, MdScopeService.RULE_SELF);
        selfViewerId = self.id();
        selfViewer = TestSession.signIn(wac, self.login());
        allViewer = TestSession.signIn(
                wac, withRule(testUsers, MdScopeService.RULE_ALL).login());

        String done = "an_done_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.sql(
                        "insert into ms_task_statuses (code, name, color, is_terminal) values (:code, 'Done', '#ffffff', true)")
                .param("code", done)
                .update();

        insideProject = (Long) fixture.create(Kind.PROJECT, true);
        outsideProject = (Long) fixture.create(Kind.PROJECT, false);
        long insideTask = fixture.task(fixture.insider, insideProject);
        long selfTask = fixture.task(selfViewerId, null);
        long outsideOpen = fixture.task(fixture.outsider, outsideProject);
        long outsideDone = fixture.task(fixture.outsider, outsideProject);
        long outsideOverdue = fixture.task(fixture.outsider, outsideProject);
        jdbc.sql("update ms_tasks set status_code = :done, resolved_time = now() where id = :id")
                .param("done", done)
                .param("id", outsideDone)
                .update();
        jdbc.sql("update ms_tasks set end_time = now() - interval '1 day' where id = :id")
                .param("id", outsideOverdue)
                .update();
        visibleTasks = List.of(insideTask, selfTask);
        hiddenTasks = List.of(outsideOpen, outsideDone, outsideOverdue);
    }

    @Test
    @DisplayName("ADR-0013: a UNITS viewer's summary counts only the tasks, projects and users of the scope")
    void unitsSummaryCountsOnlyTheScope() throws Exception {
        Map<String, Object> summary = object(unitsViewer, "/summary");
        // The insider's task and the SELF viewer's task have a participant in unit A; the outsider's three do not.
        assertThat(number(summary, "totalTasks")).isEqualTo(2);
        assertThat(number(summary, "activeTasks")).isEqualTo(2);
        assertThat(number(summary, "completedTasks")).isZero();
        assertThat(number(summary, "overdueTasks")).isZero();
        assertThat(number(summary, "createdLast7d")).isEqualTo(2);
        assertThat(number(summary, "completedLast7d")).isZero();
        assertThat(number(summary, "activeProjectsCount")).isEqualTo(1);
        assertThat(number(summary, "activeUsersCount")).isEqualTo(activeUsersOfUnitA());
    }

    @Test
    @DisplayName("ADR-0013: a UNITS viewer's trends, projects and workload never show what lies outside the scope")
    void unitsRowsStayInsideTheScope() throws Exception {
        assertThat(array(unitsViewer, "/trends?range=7d").stream()
                        .mapToLong(day -> number(day, "createdCount"))
                        .sum())
                .isEqualTo(2);

        List<Map<String, Object>> projects = array(unitsViewer, "/projects");
        assertThat(projects).extracting(row -> number(row, "projectId")).containsExactly(insideProject);
        assertThat(projects)
                .allSatisfy(row -> assertThat(number(row, "totalTasks")).isEqualTo(1));

        List<Map<String, Object>> workload = array(unitsViewer, "/workload");
        Set<Long> named = workload.stream().map(row -> number(row, "userId")).collect(Collectors.toSet());
        assertThat(named).doesNotContain(fixture.outsider).contains(fixture.insider);
        assertThat(named).isSubsetOf(usersOfUnitA());
        assertThat(workload)
                .filteredOn(row -> number(row, "userId") == fixture.insider)
                .singleElement()
                .satisfies(row -> assertThat(number(row, "assignedTasks")).isEqualTo(1));
    }

    @Test
    @DisplayName("ADR-0013: a SELF viewer sees the figures of their own tasks and only themselves")
    void selfSeesOnlyOwnFigures() throws Exception {
        Map<String, Object> summary = object(selfViewer, "/summary");
        assertThat(number(summary, "totalTasks")).isEqualTo(1);
        assertThat(number(summary, "activeProjectsCount")).isZero();
        assertThat(number(summary, "activeUsersCount")).isEqualTo(1);
        assertThat(array(selfViewer, "/projects")).isEmpty();
        assertThat(array(selfViewer, "/workload")).singleElement().satisfies(row -> {
            assertThat(number(row, "userId")).isEqualTo(selfViewerId);
            assertThat(number(row, "assignedTasks")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("ADR-0013: an ALL viewer keeps the figures of the whole installation")
    void allKeepsTheWholeInstallation() throws Exception {
        Map<String, Object> summary = object(allViewer, "/summary");
        assertThat(number(summary, "totalTasks")).isEqualTo(count("select count(*) from ms_tasks"));
        assertThat(number(summary, "overdueTasks")).isPositive();
        assertThat(number(summary, "completedTasks")).isPositive();
        assertThat(number(summary, "activeProjectsCount"))
                .isEqualTo(count("select count(*) from ms_task_projects where archived_at is null"));
        assertThat(number(summary, "activeUsersCount"))
                .isEqualTo(count("select count(*) from md_users where state = 'A'"));
        assertThat(array(allViewer, "/projects"))
                .hasSize((int) Math.min(15, count("select count(*) from ms_task_projects where archived_at is null")));
        assertThat(array(allViewer, "/workload"))
                .hasSize((int) Math.min(15, count("select count(*) from md_users where state = 'A'")));
        assertThat(outsideProject).isPositive();
    }

    @Test
    @DisplayName("ADR-0013: the dashboard's task export lists only the tasks of the viewer's scope")
    void taskExportStaysInsideTheScope() throws Exception {
        MockHttpServletResponse response = unitsViewer.send(get("/api/v1/reports/tasks/export?format=csv"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Set<Long> exported = response.getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> Long.valueOf(line.substring(0, line.indexOf(';'))))
                .collect(Collectors.toSet());
        assertThat(exported).containsExactlyInAnyOrderElementsOf(visibleTasks);
        assertThat(exported).doesNotContainAnyElementsOf(hiddenTasks);
    }

    /** A user of unit A with every administrator right whose role rule is {@code rule}. */
    private TestUser withRule(TestUsers testUsers, String rule) {
        TestUser user = testUsers.withRightsOf("chief_admin", fixture.unitA);
        long role = jdbc.sql("select role_id from md_user_roles where user_id = :user")
                .param("user", user.id())
                .query(Long.class)
                .single();
        scopeRepository.setRoleRule(role, rule);
        scopes.recalculateFor(user.id());
        return user;
    }

    private Set<Long> usersOfUnitA() {
        return Set.copyOf(
                jdbc.sql("""
                        select u.id from md_users u
                        where u.org_unit_id = :unit
                           or exists (select 1 from md_user_org_units uou
                                      where uou.user_id = u.id and uou.org_unit_id = :unit)
                        """).param("unit", fixture.unitA).query(Long.class).list());
    }

    private long activeUsersOfUnitA() {
        return jdbc.sql("select count(*) from md_users where state = 'A' and id in (:ids)")
                .param("ids", usersOfUnitA())
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private Map<String, Object> object(TestSession session, String path) throws Exception {
        return TestSession.object(ok(session, path));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> array(TestSession session, String path) throws Exception {
        return TestSession.array(ok(session, path)).stream()
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    private static MockHttpServletResponse ok(TestSession session, String path) throws Exception {
        MockHttpServletResponse response = session.send(get(ANALYTICS + path));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
