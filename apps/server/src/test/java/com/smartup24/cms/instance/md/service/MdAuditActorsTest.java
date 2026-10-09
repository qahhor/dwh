package com.smartup24.cms.instance.md.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.instance.warehouse.migration.Migrator;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The technical account {@code system} is created by the foundation code, not by a migration: after the
 * migrations {@code md_users} is empty on a fresh database (only the initial setup creates users).
 */
class MdAuditActorsTest extends EmbeddedPostgresTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdAuditActors actors;

    @Test
    void migrationsCreateNoUsers() {
        TestDatabases.createDatabase("fnd_no_user_seed");
        Migrator.migrateOltp(TestDatabases.database("fnd_no_user_seed"));

        JdbcClient fresh = JdbcClient.create(TestDatabases.database("fnd_no_user_seed"));
        Long users =
                fresh.sql("select count(*) from md_users").query(Long.class).single();

        assertThat(users).isZero();
    }

    @Test
    void lazyPathRefusesOnEmptyUsers() {
        TestDatabases.createDatabase("fnd_no_user_seed");
        Migrator.migrateOltp(TestDatabases.database("fnd_no_user_seed"));

        JdbcClient fresh = JdbcClient.create(TestDatabases.database("fnd_no_user_seed"));
        MdAuditActors freshActors = new MdAuditActors(fresh);

        assertThatThrownBy(freshActors::system)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before the instance was set up");
        assertThat(fresh.sql("select count(*) from md_users").query(Long.class).single())
                .isZero();
    }

    @Test
    void ensureUserCreatesOnceAndIsIdempotent() {
        String login = "system-test-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            long first = MdAuditActors.ensureUser(jdbc, login, "TEST system", login + "@localhost");
            long second = MdAuditActors.ensureUser(jdbc, login, "TEST system", login + "@localhost");

            assertThat(second).isEqualTo(first);
            assertThat(jdbc.sql("select count(*) from md_users where login = :login")
                            .param("login", login)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
            assertThat(jdbc.sql("select state from md_users where login = :login")
                            .param("login", login)
                            .query(String.class)
                            .single())
                    .isEqualTo("P");
            assertThat(jdbc.sql("select password_hash is null from md_users where login = :login")
                            .param("login", login)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
        } finally {
            jdbc.sql("delete from md_users where login = :login")
                    .param("login", login)
                    .update();
        }
    }

    @Test
    void systemActorResolvesToSystemLogin() {
        long expected = jdbc.sql("select id from md_users where login = :login")
                .param("login", AuditActor.SYSTEM)
                .query(Long.class)
                .single();

        assertThat(actors.system().userId()).isEqualTo(expected);
        assertThat(actors.system().userId()).isEqualTo(expected);
    }
}
