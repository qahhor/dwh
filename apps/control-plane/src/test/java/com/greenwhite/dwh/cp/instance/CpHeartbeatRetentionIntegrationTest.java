package com.greenwhite.dwh.cp.instance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "dwh.schema-gate.enabled=false",
        "dwh.cp.admin-login=",
        "dwh.cp.admin-email=",
        "dwh.cp.admin-password="
})
@Testcontainers
class CpHeartbeatRetentionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long instanceId;

    @BeforeEach
    void registerInstance() {
        int sequence = CLIENT_SEQUENCE.incrementAndGet();
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values (:code, :name, 'M')
                        returning id
                        """)
                .param("code", "retention-" + sequence)
                .param("name", "Retention " + sequence)
                .query(Long.class)
                .single();
        instanceId = jdbc.sql("""
                        insert into cp_instances(client_id, environment, url, license_status)
                        values (:clientId, 'production', :url, 'ACTIVE')
                        returning id
                        """)
                .param("clientId", clientId)
                .param("url", "https://retention-" + sequence + ".invalid")
                .query(Long.class)
                .single();
    }

    @Test
    void aggregatesCompletedUtcDaysAndPrunesStrictBoundariesIdempotentlyInBatches() {
        insertHeartbeat(NOW.minus(Duration.ofDays(31)), "1.0.0", 31_000, 31, 31);
        insertHeartbeat(NOW.minus(Duration.ofDays(30)), "1.1.0", 30_000, 30, 30);
        insertHeartbeat(NOW.minus(Duration.ofDays(1)), "1.2.0", 1_000, 1, 1);

        LocalDate today = NOW.atZone(ZoneOffset.UTC).toLocalDate();
        insertDaily(today.minusDays(398));
        insertDaily(today.minusDays(397));
        insertDaily(today.minusDays(396));
        insertDaily(today.minusDays(395));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CpHeartbeatRetentionJob job = job(meters, 2);
        job.aggregateAndPrune();

        assertThat(rawHeartbeatCountAt(NOW.minus(Duration.ofDays(31)))).isZero();
        assertThat(rawHeartbeatCountAt(NOW.minus(Duration.ofDays(30)))).isOne();
        assertThat(rawHeartbeatCountAt(NOW.minus(Duration.ofDays(1)))).isOne();

        DailyAggregate aggregate = jdbc.sql("""
                        select sample_count, max_storage_used_bytes, max_active_users,
                               max_outbox_pending, last_app_version, last_schema_version
                        from cp_heartbeat_daily
                        where instance_id = :instanceId and day = :day
                        """)
                .param("instanceId", instanceId)
                .param("day", today.minusDays(31))
                .query((rs, rowNum) -> new DailyAggregate(
                        rs.getLong("sample_count"),
                        rs.getLong("max_storage_used_bytes"),
                        rs.getLong("max_active_users"),
                        rs.getLong("max_outbox_pending"),
                        rs.getString("last_app_version"),
                        rs.getString("last_schema_version")))
                .single();
        assertThat(aggregate).isEqualTo(new DailyAggregate(1, 31_000, 31, 31, "1.0.0", "006"));

        assertThat(dailyCountBefore(today.minusDays(395))).isZero();
        assertThat(dailyCountAt(today.minusDays(395))).isOne();
        assertThat(meters.counter("dwh_cp_heartbeat_retention_deleted_total").count()).isEqualTo(4);

        job.aggregateAndPrune();

        assertThat(dailyCountBefore(today.minusDays(395))).isZero();
        assertThat(dailyCountAt(today.minusDays(31))).isOne();
        assertThat(meters.counter("dwh_cp_heartbeat_retention_deleted_total").count()).isEqualTo(4);
        assertThat(CpHeartbeatRetentionJob.DELETE_BATCH_SIZE).isEqualTo(10_000);
        assertThat(CpHeartbeatRetentionJob.MAX_DELETE_BATCHES).isEqualTo(100);
    }

    @Test
    void advisoryLockContentionSkipsAggregationAndDeletion() throws Exception {
        Instant expired = NOW.minus(Duration.ofDays(31));
        insertHeartbeat(expired, "1.0.0", 1, 1, 1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        try (Connection lockConnection = dataSource.getConnection();
             var lock = lockConnection.prepareStatement("select pg_advisory_lock(?)")) {
            lock.setLong(1, CpHeartbeatRetentionJob.ADVISORY_LOCK_KEY);
            lock.execute();

            job(meters, 10_000).aggregateAndPrune();
        }

        assertThat(rawHeartbeatCountAt(expired)).isOne();
        assertThat(meters.counter("dwh_cp_heartbeat_retention_deleted_total").count()).isZero();
        assertThat(meters.counter("dwh_cp_heartbeat_retention_failures_total").count()).isZero();
    }

    @Test
    void recordsFailureMetricAndPropagatesRetentionFailure() {
        PlatformTransactionManager failingTransactions = mock(PlatformTransactionManager.class);
        when(failingTransactions.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CpHeartbeatRetentionJob job = new CpHeartbeatRetentionJob(
                jdbc,
                failingTransactions,
                meters,
                Duration.ofDays(30),
                Duration.ofDays(395),
                10_000,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(job::aggregateAndPrune)
                .isInstanceOf(IllegalStateException.class);
        assertThat(meters.counter("dwh_cp_heartbeat_retention_failures_total").count()).isOne();
    }

    private CpHeartbeatRetentionJob job(SimpleMeterRegistry meters, int deleteBatchSize) {
        return new CpHeartbeatRetentionJob(
                jdbc,
                transactionManager,
                meters,
                Duration.ofDays(30),
                Duration.ofDays(395),
                deleteBatchSize,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void insertHeartbeat(Instant receivedAt,
                                 String appVersion,
                                 long storageUsed,
                                 long activeUsers,
                                 long outboxPending) {
        jdbc.sql("""
                        insert into cp_instance_heartbeats(
                            instance_id, app_version, schema_version, metrics,
                            storage_used_bytes, active_users, outbox_pending, received_at)
                        values (:instanceId, :appVersion, '006', '{}'::jsonb,
                                :storageUsed, :activeUsers, :outboxPending, :receivedAt)
                        """)
                .param("instanceId", instanceId)
                .param("appVersion", appVersion)
                .param("storageUsed", storageUsed)
                .param("activeUsers", activeUsers)
                .param("outboxPending", outboxPending)
                .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertDaily(LocalDate day) {
        jdbc.sql("""
                        insert into cp_heartbeat_daily(
                            instance_id, day, sample_count, last_app_version, last_schema_version)
                        values (:instanceId, :day, 1, 'old', '001')
                        """)
                .param("instanceId", instanceId)
                .param("day", day)
                .update();
    }

    private long rawHeartbeatCountAt(Instant receivedAt) {
        return jdbc.sql("""
                        select count(*) from cp_instance_heartbeats
                        where instance_id = :instanceId and received_at = :receivedAt
                        """)
                .param("instanceId", instanceId)
                .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private long dailyCountBefore(LocalDate boundary) {
        return jdbc.sql("""
                        select count(*) from cp_heartbeat_daily
                        where instance_id = :instanceId and day < :boundary
                        """)
                .param("instanceId", instanceId)
                .param("boundary", boundary)
                .query(Long.class)
                .single();
    }

    private long dailyCountAt(LocalDate day) {
        return jdbc.sql("""
                        select count(*) from cp_heartbeat_daily
                        where instance_id = :instanceId and day = :day
                        """)
                .param("instanceId", instanceId)
                .param("day", day)
                .query(Long.class)
                .single();
    }

    private record DailyAggregate(
            long sampleCount,
            long maxStorageUsedBytes,
            long maxActiveUsers,
            long maxOutboxPending,
            String lastAppVersion,
            String lastSchemaVersion) {
    }
}
