package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.ms.task.service.MsProjectEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
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

/** Adding a project member cannot implicitly change access outside the displayed page. */
class MsProjectMemberSafetyIntegrationTest extends EmbeddedPostgresTest {

    private static final String PROJECTS = "/api/v1/entities/" + MsProjectEntity.CODE;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    private TestSession session;
    private TestUser member;
    private long project;
    private String tag;

    @BeforeEach
    void createProject() throws Exception {
        TestUsers users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        long unit = users.unit("member-safety-" + tag);
        TestUser owner = users.withRights(Map.of("tasks.projects", Set.of("view", "create", "update")), unit);
        member = users.withRights(Map.of(), unit);
        session = TestSession.signIn(wac, owner.login());
        MockHttpServletResponse created = session.send(post(PROJECTS), Map.of("name", "Member safety " + tag));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        project = ((Number) TestSession.object(created).get("id")).longValue();
    }

    @Test
    void duplicateBeyondFirstPagePreservesAccessRevisionAndAudit() throws Exception {
        assertThat(add(1, Map.of("userId", member.id(), "accessKind", "W")).getStatus())
                .isEqualTo(200);
        seedEarlierMembers();
        Map<String, Object> page = TestSession.object(session.send(
                get("/api/v1/tasks/projects/" + project + "/members/page").param("limit", "50")));
        List<?> loaded = (List<?>) page.get("items");
        assertThat(loaded).hasSize(50);
        assertThat(loaded.stream()
                        .map(item -> ((Number) ((Map<?, ?>) item).get("userId")).longValue())
                        .toList())
                .doesNotContain(member.id());
        assertThat(page.get("hasMore")).isEqualTo(true);
        long auditBefore = audits();

        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.of("userId", member.id(), "accessKind", "R"),
                Map.of("userId", member.id(), "accessKind", "R", "updateExisting", false))) {
            MockHttpServletResponse duplicate = add(2, params);
            assertThat(duplicate.getStatus()).as(duplicate.getContentAsString()).isEqualTo(422);
            assertThat(duplicate.getContentAsString()).contains("params.userId", "error.project.member_exists");
            assertThat(access()).isEqualTo("W");
            assertThat(TestSession.object(session.send(get(PROJECTS + "/" + project))))
                    .containsEntry("revision", 2);
            assertThat(audits()).isEqualTo(auditBefore);
        }
    }

    @Test
    void explicitUpdateRequiresCurrentRevisionAndChangesOnlyNamedMember() throws Exception {
        assertThat(add(1, Map.of("userId", member.id(), "accessKind", "W")).getStatus())
                .isEqualTo(200);
        Map<String, Object> update = Map.of("userId", member.id(), "accessKind", "R", "updateExisting", true);
        long auditBefore = audits();

        assertThat(add(1, update).getStatus()).isEqualTo(409);
        assertThat(access()).isEqualTo("W");
        assertThat(audits()).isEqualTo(auditBefore);
        MockHttpServletResponse changed = add(2, update);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(changed)).containsEntry("revision", 3);
        assertThat(access()).isEqualTo("R");
        assertThat(audits()).isGreaterThan(auditBefore);
    }

    @Test
    void onlyBooleanTrueCanRequestExistingAccessChange() throws Exception {
        assertThat(add(1, Map.of("userId", member.id(), "accessKind", "W")).getStatus())
                .isEqualTo(200);
        long auditBefore = audits();
        MockHttpServletResponse invalid =
                add(2, Map.of("userId", member.id(), "accessKind", "R", "updateExisting", "true"));

        assertThat(invalid.getStatus()).as(invalid.getContentAsString()).isEqualTo(422);
        assertThat(invalid.getContentAsString()).contains("params.updateExisting");
        assertThat(access()).isEqualTo("W");
        assertThat(TestSession.object(session.send(get(PROJECTS + "/" + project))))
                .containsEntry("revision", 2);
        assertThat(audits()).isEqualTo(auditBefore);
    }

    private MockHttpServletResponse add(long revision, Map<String, Object> params) throws Exception {
        return session.send(
                post(PROJECTS + "/" + project + "/actions/add_member").header("If-Match", "\"" + revision + "\""),
                params);
    }

    private String access() {
        return jdbc.sql(
                        "select access_kind from ms_task_project_members where project_id = :project and user_id = :user")
                .param("project", project)
                .param("user", member.id())
                .query(String.class)
                .single();
    }

    private long audits() {
        return jdbc.sql("""
                        select count(*) from audit_log
                        where (table_name = 'ms_task_projects' and row_pk = :project)
                           or (table_name = 'ms_task_project_members' and row_pk = :member)
                        """)
                .param("project", String.valueOf(project))
                .param("member", project + ":" + member.id())
                .query(Long.class)
                .single();
    }

    private void seedEarlierMembers() {
        jdbc.sql("update md_users set name = 'ZZZ last member' where id = :user")
                .param("user", member.id())
                .update();
        jdbc.sql("""
                        with users as (
                            insert into md_users (name, login, email)
                            select 'AAA member ' || n, :tag || '-' || n, :tag || '-' || n || '@example.invalid'
                            from generate_series(1, 50) n
                            returning id
                        )
                        insert into ms_task_project_members (project_id, user_id, access_kind)
                        select :project, id, 'W' from users
                        """).param("tag", tag).param("project", project).update();
    }
}
