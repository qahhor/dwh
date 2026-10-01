package com.smartup24.cms.instance.common.security;

import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    static final String PASSWORD = "StrongPassword2026!";

    final JdbcClient jdbc;
    final MsTaskService tasks;
    final MfFileService files;
    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final long unitA;
    final long unitB;
    final long viewer;
    final String viewerLogin;
    final long insider;
    final long outsider;
    final UUID viewerFile;
    final long statusId;

    ScopeFixture(
            JdbcClient jdbc,
            MdUserService users,
            MdScopeService scopes,
            MdScopeRepository scopeRepository,
            MdRoleRepository roles,
            MsTaskService tasks,
            MfFileService files) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.files = files;
        long root = root();
        unitA = unit(root, "a");
        unitB = unit(root, "b");

        long role = roles.create("TEST scope matrix " + tag, null, "A", 900).id();
        jdbc.sql("""
                        insert into md_role_permissions (role_id, form_code, action)
                        select :role, rp.form_code, rp.action
                        from md_role_permissions rp
                        join md_roles r on r.id = rp.role_id
                        where r.pcode = 'chief_admin'
                        """).param("role", role).update();
        scopeRepository.setRoleRule(role, MdScopeService.RULE_UNITS);

        viewerLogin = "scope-viewer-" + tag;
        Long system = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        users.createUser(
                "TEST " + viewerLogin,
                viewerLogin,
                viewerLogin + "@test.local",
                null,
                PASSWORD,
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                List.of(role),
                system);
        viewer = jdbc.sql("select id from md_users where login = :login")
                .param("login", viewerLogin)
                .query(Long.class)
                .single();
        scopeRepository.replaceUserOrgUnits(viewer, List.of(unitA));
        scopes.recalculateFor(viewer);

        insider = user(unitA);
        outsider = user(unitB);
        viewerFile = upload(viewer);
        statusId = jdbc.sql("select id from ms_task_statuses order by id limit 1")
                .query(Long.class)
                .single();
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
            case TASK ->
                tasks.createTask(null, null, "TEST scope task", "", "medium", null, null, null, null, null, null, owner)
                        .id();
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

    private long root() {
        jdbc.sql("""
                        insert into md_org_units (parent_id, code, name, kind)
                        values (null, 'scope-root', 'TEST root', 'company')
                        on conflict do nothing
                        """).update();
        return jdbc.sql("select id from md_org_units where parent_id is null")
                .query(Long.class)
                .single();
    }

    private long unit(long parent, String name) {
        return jdbc.sql("""
                        insert into md_org_units (parent_id, code, name)
                        values (:parent, :code, :name) returning id
                        """)
                .param("parent", parent)
                .param("code", "scope-" + name + "-" + tag)
                .param("name", "TEST unit " + name)
                .query(Long.class)
                .single();
    }
}
