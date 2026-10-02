package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class TaskConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("task_concurrency_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static JdbcClient jdbc;
    static DriverManagerDataSource dataSource;
    static KauthSessionRepository sessionRepository;
    static KauthApiTokenRepository apiTokenRepository;
    static MdScopeRepository scopeRepository;
    static MdRoleRepository roles;
    static MdScopeService scopes;
    static Long testUserId;
    static Long rootUnit;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        var objectMapper = new ObjectMapper();

        sessionRepository = new KauthSessionRepository(jdbc);
        apiTokenRepository = new KauthApiTokenRepository(jdbc);

        var audit = new AuditLogService(new AuditLogRepository(jdbc, objectMapper), null, new AuditDataRedactor());
        scopeRepository = new MdScopeRepository(jdbc);
        roles = new MdRoleRepository(jdbc);
        scopes = new MdScopeService(
                scopeRepository,
                new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                audit);

        rootUnit = orgUnit(null, "root");
        testUserId = user("ConcurrencyActor", rootUnit);
        assignScope(testUserId, "ALL", List.of());
    }

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
    }

    @Test
    void p02_duplicateIndexesAreDropped() {
        List<String> indexes = jdbc.sql("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'ms_tasks'
                """).query(String.class).list();

        assertThat(indexes).doesNotContain("idx_ms_tasks_status_id");
        assertThat(indexes).doesNotContain("idx_ms_tasks_project_id");

        // A task keeps the code of its status (ADR-0032, 8): the index follows the column.
        assertThat(indexes).contains("ms_tasks_status_code_idx");
        assertThat(indexes).contains("ms_tasks_project_idx");
    }

    @Test
    void p01_kauthSessionActivityCoalescing() {
        Long user = user("SessionUser", rootUnit);
        var session = sessionRepository.create(
                user, 0L, "hash-" + SEQUENCE.incrementAndGet(), "127.0.0.1", "agent", "desktop");

        Instant initialSeen = session.lastSeenAt();

        // Immediate touch within 60s -> should be coalesced (0 rows updated)
        sessionRepository.updateLastSeen(session.id());
        var fetched = sessionRepository.findActiveById(session.id()).orElseThrow();
        assertThat(fetched.lastSeenAt()).isEqualTo(initialSeen);

        // Age session past 60s
        jdbc.sql("update kauth_sessions set last_seen_at = now() - interval '65 seconds' where id = :id")
                .param("id", session.id())
                .update();

        // Touch after 65s -> should update last_seen_at
        sessionRepository.updateLastSeen(session.id());
        var updated = sessionRepository.findActiveById(session.id()).orElseThrow();
        assertThat(updated.lastSeenAt()).isAfter(initialSeen);
    }

    @Test
    void p01_kauthApiTokenActivityCoalescing() {
        Long user = user("TokenUser", rootUnit);
        var token = apiTokenRepository.create(
                user, 0L, "Token " + SEQUENCE.incrementAndGet(), "smc_", "hash-" + SEQUENCE.incrementAndGet(), null);

        assertThat(token.lastUsedAt()).isNull();

        // First touch initializes last_used_at
        apiTokenRepository.updateLastUsed(token.id());
        var firstTouch = apiTokenRepository.findActiveById(token.id()).orElseThrow();
        Instant initialUsed = firstTouch.lastUsedAt();
        assertThat(initialUsed).isNotNull();

        // Immediate touch within 60s -> coalesced (no change)
        apiTokenRepository.updateLastUsed(token.id());
        var fetched = apiTokenRepository.findActiveById(token.id()).orElseThrow();
        assertThat(fetched.lastUsedAt()).isEqualTo(initialUsed);

        // Age token past 60s
        jdbc.sql("update kauth_api_tokens set last_used_at = now() - interval '65 seconds' where id = :id")
                .param("id", token.id())
                .update();

        // Touch after 65s -> should update last_used_at
        apiTokenRepository.updateLastUsed(token.id());
        var updated = apiTokenRepository.findActiveById(token.id()).orElseThrow();
        assertThat(updated.lastUsedAt()).isAfter(initialUsed);
    }

    private static Long user(String prefix, Long orgUnitId) {
        String login = (prefix + "-" + SEQUENCE.incrementAndGet()).toLowerCase();
        return jdbc.sql("""
                insert into md_users (name, login, email, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change, org_unit_id)
                values (:name, :login, :login || '@example.invalid', 'x', 'A', 'ru', 'UTC',
                        '{}', false, false, :orgUnitId)
                returning id
                """)
                .param("name", prefix)
                .param("login", login)
                .param("orgUnitId", orgUnitId)
                .query(Long.class)
                .single();
    }

    private static Long orgUnit(Long parent, String code) {
        return jdbc.sql("""
                insert into md_org_units (parent_id, code, name, kind, state, order_no)
                values (:parent, :code, :name, 'department', 'A', 1) returning id
                """)
                .param("parent", parent)
                .param("code", code + "-" + SEQUENCE.incrementAndGet())
                .param("name", code)
                .query(Long.class)
                .single();
    }

    private static void assignScope(Long userId, String rule, List<Long> orgUnitIds) {
        var role = roles.create("Concurrency role " + rule + " " + SEQUENCE.incrementAndGet(), null, "A", 100);
        scopeRepository.setRoleRule(role.id(), rule);
        roles.assignRolesToUser(userId, List.of(role.id()));
        scopeRepository.replaceUserOrgUnits(userId, orgUnitIds);
        scopes.recalculateFor(userId);
    }
}
