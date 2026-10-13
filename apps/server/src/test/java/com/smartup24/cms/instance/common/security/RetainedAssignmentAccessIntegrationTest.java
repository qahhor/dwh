package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/** ADR-0032: replay preserves the endpoint's permissions and still checks the current row scope. */
class RetainedAssignmentAccessIntegrationTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdPermissionService permissions;

    @Test
    void assignmentReplayNeedsNoUserViewRightAndStopsWhenTheTargetMoves() throws Exception {
        TestUsers users = TestUsers.of(wac);
        long unit = users.unit("retained-assignments");
        var operator = users.withRights(Map.of("md.assignments", List.of("assign")), unit);
        var target = users.withRights(Map.of(), unit);
        jdbc.sql("update md_users set org_unit_id = null where id = :id")
                .param("id", target.id())
                .update();
        TestSession session = TestSession.signIn(wac, operator.login());
        String path = "/api/v1/iam/users/" + target.id() + "/permissions";
        String etag = "\"" + revision(target.id()) + "\"";
        Map<String, Object> body = Map.of("grants", List.of());
        UUID key = UUID.randomUUID();
        assertThat(session.send(get("/api/v1/entities/md.users/" + target.id())).getStatus())
                .isEqualTo(404);
        var first = session.send(put(path).header("If-Match", etag), body, key);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        long savedRevision = revision(target.id());
        var replay = session.send(put(path).header("If-Match", etag), body, key);
        assertThat(replay.getStatus()).as(replay.getContentAsString()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isEqualTo(first.getHeader("ETag"));
        assertThat(TestSession.object(replay)).isEqualTo(TestSession.object(first));
        assertThat(revision(target.id())).isEqualTo(savedRevision);

        long version = permissions.getPermissionVersion(operator.id());
        scopes.assignUserOrgUnits(target.id(), List.of(users.unit("outside-assignments")), savedRevision);
        assertThat(permissions.getPermissionVersion(operator.id())).isEqualTo(version);
        assertThat(scopes.canAccessUser(operator.id(), target.id())).isFalse();
        var refused = session.send(put(path).header("If-Match", etag), body, key);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getHeader("Idempotent-Replay")).isNull();
    }

    private long revision(long id) {
        return jdbc.sql("select revision from md_users where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
