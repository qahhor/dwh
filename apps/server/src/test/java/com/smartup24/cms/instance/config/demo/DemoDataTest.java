package com.smartup24.cms.instance.config.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrapProperties;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 6.5: the demo profile fills a fresh stand through the entity runtime, and a second start adds
 * nothing. The records are written inside one transaction that is rolled back, so the shared test database keeps no
 * demo data for the other test classes.
 */
class DemoDataTest extends EmbeddedPostgresTest {

    @Autowired
    private EntityRuntime runtime;

    @Autowired
    private MdUserService users;

    @Autowired
    private MdPermissionService permissions;

    @Autowired
    private ModuleRegistryService modules;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private CacheManager caches;

    @AfterEach
    void forgetCachedModules() {
        caches.getCacheNames().forEach(name -> {
            var cache = caches.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    @Test
    @DisplayName("the first run creates users, projects, tasks, notes and orders; the second creates nothing")
    void seedsOnceThroughTheRuntime() {
        tx.executeWithoutResult(status -> {
            String login = admin();
            DemoData demo = demo(login);

            int people = DemoDataset.PEOPLE.size();
            int tasks = DemoDataset.PROJECTS.stream()
                    .mapToInt(project -> project.tasks().size())
                    .sum();
            int expected =
                    people + DemoDataset.PROJECTS.size() + tasks + DemoDataset.NOTES.size() + DemoDataset.ORDERS.size();
            assertThat(demo.seed()).isEqualTo(expected);
            assertThat(demo.seed()).as("a restart adds nothing").isZero();

            assertThat(count("select count(*) from md_users where login like 'demo.%'"))
                    .isEqualTo(people);
            assertThat(count("select count(*) from ms_tasks t join ms_task_projects p on p.id = t.project_id"
                            + " where p.name like 'Демо:%'"))
                    .isEqualTo(tasks);
            assertThat(count("select count(*) from ex_orders where customer like 'Демо:%' and status = 'posted'"))
                    .as("the order marked posted went through its transition")
                    .isEqualTo(1);
            assertThat(modules.isModuleActive(DemoData.ORDERS_MODULE)).isTrue();
            assertThat(SecurityContext.getPrincipal())
                    .as("the seeder leaves no principal behind")
                    .isNull();
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("without the bootstrap administrator the seeder refuses to start")
    void needsTheAdministrator() {
        DemoData demo = demo("no-such-admin-" + UUID.randomUUID());
        assertThatThrownBy(demo::seed).isInstanceOf(IllegalStateException.class);
    }

    private DemoData demo(String login) {
        var instance = new InstanceBootstrapProperties("TEST", "Test", "S", login, login + "@example.com", "unused");
        return new DemoData(runtime, users, permissions, modules, instance, mapper);
    }

    /** An administrator as the instance bootstrap creates one: the role admin and its effective rights. */
    private String admin() {
        String login = "demo-admin-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values ('Demo admin', :login, :email, 'x', 'A', 'ru', 'UTC', '{}'::jsonb, false, true)
                        returning id
                        """)
                .param("login", login)
                .param("email", login + "@example.com")
                .query(Long.class)
                .single();
        jdbc.sql("insert into md_user_roles (user_id, role_id) select :id, id from md_roles where pcode = 'admin'")
                .param("id", id)
                .update();
        permissions.recalculateEffectivePermissions(id);
        return login;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
