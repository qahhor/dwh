package com.smartup24.cms.instance.common.security;

import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The records of {@link ScopeByIdMatrixIntegrationTest}: two org units, a viewer whose rule is UNITS on unit A
 * with every permission of the instance administrator, and for each kind of record a fresh one inside the
 * viewer's scope (owned by someone in unit A, or by the viewer for personal notes) or outside it (owned by
 * someone in unit B, or by someone else for notes).
 */
final class ScopeFixture {

    /** The kinds of scoped records a by-id handler addresses (ADR-0013). */
    enum Kind {
        USER,
        PROJECT,
        TASK,
        NOTE,
        FILE
    }

    final JdbcClient jdbc;
    final MfFileService files;
    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final long unitA;
    final long unitB;
    final long viewer;
    final String viewerLogin;
    final long insider;
    final long outsider;
    final UUID viewerFile;

    ScopeFixture(
            JdbcClient jdbc,
            MdUserService users,
            MdScopeService scopes,
            MdScopeRepository scopeRepository,
            MdRoleRepository roles,
            MfFileService files) {
        this.jdbc = jdbc;
        this.files = files;
        TestUsers testUsers = new TestUsers(jdbc, users, scopes, scopeRepository, roles);
        unitA = testUsers.unit("scope-a");
        unitB = testUsers.unit("scope-b");
        TestUser viewerUser = testUsers.withRightsOf("chief_admin", unitA);
        viewer = viewerUser.id();
        viewerLogin = viewerUser.login();

        insider = user(unitA);
        outsider = user(unitB);
        viewerFile = upload(viewer);
    }

    /** A fresh record of the kind, inside the viewer's scope or outside it. */
    Object create(Kind kind, boolean inside) {
        long owner = inside ? insider : outsider;
        return switch (kind) {
            case USER -> user(inside ? unitA : unitB);
            case PROJECT ->
                jdbc.sql("""
                            insert into ms_task_projects (name, created_by)
                            values (:name, :owner) returning id
                            """)
                        .param("name", "TEST scope project " + UUID.randomUUID())
                        .param("owner", owner)
                        .query(Long.class)
                        .single();
            case TASK -> task(owner, null);
            case NOTE ->
                jdbc.sql("""
                            insert into ms_notes (title, content_md, created_by, modified_by)
                            values ('TEST scope note', 'body', :owner, :owner) returning id
                            """)
                        .param("owner", inside ? viewer : insider)
                        .query(Long.class)
                        .single();
            case FILE -> upload(owner);
        };
    }

    /** The current revision of a record for {@code If-Match}; files have none. */
    String ifMatch(Kind kind, Object id) {
        String table = switch (kind) {
            case USER -> "md_users";
            case PROJECT -> "ms_task_projects";
            case TASK -> "ms_tasks";
            case NOTE -> "ms_notes";
            case FILE -> null;
        };
        if (table == null) {
            return null;
        }
        long revision = jdbc.sql("select revision from " + table + " where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        return "\"" + revision + "\"";
    }

    /** An open session of a user, for the handler that closes one session of a user. */
    long session(Object userId) {
        return jdbc.sql("""
                        insert into kauth_sessions (user_id, token_hash, ip, user_agent)
                        values (:user, :hash, '127.0.0.1', 'scope matrix') returning id
                        """)
                .param("user", userId)
                .param("hash", "scope-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    /** The viewer's own file, attached to a task, for the handler that detaches a file from a task. */
    UUID attached(Object taskId) {
        jdbc.sql("insert into ms_task_files (task_id, file_id) values (:task, :file) on conflict do nothing")
                .param("task", taskId)
                .param("file", viewerFile)
                .update();
        return viewerFile;
    }

    /**
     * A task of {@code owner}, written as the general runtime writes one: the owner is its reporter and takes part
     * in it as its author (ADR-0013: the author and the participants see a task).
     */
    long task(long owner, @Nullable Long project) {
        long id = jdbc.sql("""
                        insert into ms_tasks (project_id, title, priority, reporter_id, created_by, modified_by)
                        values (:project, 'TEST scope task', 'medium', :owner, :owner, :owner) returning id
                        """)
                .param("project", project)
                .param("owner", owner)
                .query(Long.class)
                .single();
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind) values (:task, :user, 'A')")
                .param("task", id)
                .param("user", owner)
                .update();
        return id;
    }

    private long user(long unit) {
        String login = "scope-user-" + UUID.randomUUID().toString().substring(0, 12);
        long id = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC')
                        returning id
                        """).param("login", login).query(Long.class).single();
        jdbc.sql("insert into md_user_org_units (user_id, org_unit_id) values (:user, :unit)")
                .param("user", id)
                .param("unit", unit)
                .update();
        return id;
    }

    private UUID upload(long owner) {
        byte[] content = ("scope " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        return files.upload("scope.txt", "text/plain", new ByteArrayInputStream(content), content.length, owner)
                .id();
    }
}
