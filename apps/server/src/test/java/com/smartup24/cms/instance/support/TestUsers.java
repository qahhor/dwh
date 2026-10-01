package com.smartup24.cms.instance.support;

import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test users with the rights and the data scope a test needs (ADR-0032, 11.3; ADR-0013): each user gets a role of their
 * own with exactly the given rights (plus {@code md.profile.view}, which every role has) and the rule {@code UNITS} on
 * one org unit, which is also their home unit. Sign them in with {@link TestSession#signIn}.
 */
public final class TestUsers {

    /** The password of every test user. */
    public static final String PASSWORD = "StrongPassword2026!";

    /** The right every role holds: signing in and the platform's own endpoints (ADR-0028). */
    public static final Map<String, List<String>> SIGNED_IN = Map.of("md.profile", List.of("view"));

    /** A user created by this helper. */
    public record TestUser(long id, String login, long unit) {}

    private final JdbcClient jdbc;
    private final MdUserService users;
    private final MdScopeService scopes;
    private final MdScopeRepository scopeRepository;
    private final MdRoleRepository roles;

    public TestUsers(
            JdbcClient jdbc,
            MdUserService users,
            MdScopeService scopes,
            MdScopeRepository scopeRepository,
            MdRoleRepository roles) {
        this.jdbc = jdbc;
        this.users = users;
        this.scopes = scopes;
        this.scopeRepository = scopeRepository;
        this.roles = roles;
    }

    /** The helper over the beans of a running application. */
    public static TestUsers of(ApplicationContext context) {
        return new TestUsers(
                context.getBean(JdbcClient.class),
                context.getBean(MdUserService.class),
                context.getBean(MdScopeService.class),
                context.getBean(MdScopeRepository.class),
                context.getBean(MdRoleRepository.class));
    }

    /** A fresh org unit under the root. */
    public long unit(String name) {
        return jdbc.sql("""
                        insert into md_org_units (parent_id, code, name)
                        values (:parent, :code, :name) returning id
                        """)
                .param("parent", root())
                .param("code", "test-" + name + "-" + tag())
                .param("name", "TEST unit " + name)
                .query(Long.class)
                .single();
    }

    /** A user whose role holds exactly {@code rights} (form → actions) and sees {@code unit} only. */
    public TestUser withRights(Map<String, ? extends Collection<String>> rights, long unit) {
        long role = role();
        grant(role, SIGNED_IN);
        grant(role, rights);
        return user(role, unit);
    }

    /** A user whose role holds every right of the system role {@code pcode} and sees {@code unit} only. */
    public TestUser withRightsOf(String pcode, long unit) {
        long role = role();
        jdbc.sql("""
                        insert into md_role_permissions (role_id, form_code, action)
                        select :role, rp.form_code, rp.action
                        from md_role_permissions rp
                        join md_roles r on r.id = rp.role_id
                        where r.pcode = :pcode
                        on conflict do nothing
                        """).param("role", role).param("pcode", pcode).update();
        return user(role, unit);
    }

    private long role() {
        long role = roles.create("TEST role " + tag(), null, "A", 900).id();
        scopeRepository.setRoleRule(role, MdScopeService.RULE_UNITS);
        return role;
    }

    private void grant(long role, Map<String, ? extends Collection<String>> rights) {
        rights.forEach((form, actions) -> actions.forEach(action -> jdbc.sql("""
                                insert into md_role_permissions (role_id, form_code, action)
                                values (:role, :form, :action) on conflict do nothing
                                """)
                .param("role", role)
                .param("form", form)
                .param("action", action)
                .update()));
    }

    private TestUser user(long role, long unit) {
        String login = "test-" + tag();
        Long system = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        users.createUser(
                "TEST " + login,
                login,
                login + "@test.local",
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
        long id = jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
        jdbc.sql("update md_users set org_unit_id = :unit where id = :id")
                .param("unit", unit)
                .param("id", id)
                .update();
        scopeRepository.replaceUserOrgUnits(id, List.of(unit));
        scopes.recalculateFor(id);
        return new TestUser(id, login, unit);
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

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
