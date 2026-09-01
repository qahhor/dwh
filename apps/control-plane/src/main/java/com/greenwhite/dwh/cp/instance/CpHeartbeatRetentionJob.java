package com.greenwhite.dwh.cp.instance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class CpHeartbeatRetentionJob {

    static final long ADVISORY_LOCK_KEY = 0x4457485F43505F48L;
    static final int DELETE_BATCH_SIZE = 10_000;
    static final int MAX_DELETE_BATCHES = 100;

    private static final Logger log = LoggerFactory.getLogger(CpHeartbeatRetentionJob.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Counter deletedCounter;
    private final Counter failureCounter;
    private final Duration rawRetention;
    private final Duration aggregateRetention;
    private final int deleteBatchSize;
    private final Clock clock;

    @Autowired
    public CpHeartbeatRetentionJob(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry,
            @Value("${dwh.cp.instance-api.raw-retention:30d}") Duration rawRetention,
            @Value("${dwh.cp.instance-api.aggregate-retention:395d}") Duration aggregateRetention) {
        this(jdbc, transactionManager, meterRegistry, rawRetention, aggregateRetention,
                DELETE_BATCH_SIZE, Clock.systemUTC());
    }

    CpHeartbeatRetentionJob(JdbcClient jdbc,
                            PlatformTransactionManager transactionManager,
                            MeterRegistry meterRegistry,
                            Duration rawRetention,
                            Duration aggregateRetention,
                            int deleteBatchSize,
                            Clock clock) {
        if (rawRetention.isZero() || rawRetention.isNegative()
                || aggregateRetention.isZero() || aggregateRetention.isNegative()
                || deleteBatchSize <= 0) {
            throw new IllegalArgumentException("Heartbeat retention settings must be positive");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deletedCounter = Counter.builder("dwh_cp_heartbeat_retention_deleted_total")
                .description("Raw heartbeat and daily aggregate rows deleted by retention")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("dwh_cp_heartbeat_retention_failures_total")
                .description("Failed heartbeat retention executions")
                .register(meterRegistry);
        this.rawRetention = rawRetention;
        this.aggregateRetention = aggregateRetention;
        this.deleteBatchSize = deleteBatchSize;
        this.clock = clock;
    }

    @Scheduled(cron = "${dwh.cp.instance-api.retention-cron:0 15 2 * * *}", zone = "GMT+05:00")
    public void aggregateAndPrune() {
        try {
            RetentionCounts counts = transactions.execute(status -> runLocked());
            if (counts == null) {
                return;
            }
            long deleted = (long) counts.rawDeleted() + counts.aggregatesDeleted();
            deletedCounter.increment(deleted);
            log.info("Heartbeat retention completed [lockId={}, aggregated={}, rawDeleted={}, aggregateDeleted={}]",
                    ADVISORY_LOCK_KEY,
                    counts.aggregated(),
                    counts.rawDeleted(),
                    counts.aggregatesDeleted());
        } catch (RuntimeException error) {
            failureCounter.increment();
            log.error("Heartbeat retention failed [lockId={}, type={}]",
                    ADVISORY_LOCK_KEY,
                    error.getClass().getSimpleName());
            throw error;
        }
    }

    private RetentionCounts runLocked() {
        boolean acquired = jdbc.sql("select pg_try_advisory_xact_lock(:lockKey)")
                .param("lockKey", ADVISORY_LOCK_KEY)
                .query(Boolean.class)
                .single();
        if (!acquired) {
            log.info("Heartbeat retention skipped because advisory lock is held [lockId={}]",
                    ADVISORY_LOCK_KEY);
            return null;
        }

        Instant now = clock.instant();
        Instant completedBefore = now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant rawBoundary = now.minus(rawRetention);
        LocalDate aggregateBoundary = now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .minusDays(aggregateRetention.toDays());

        int aggregated = aggregateCompletedDays(completedBefore);
        int rawDeleted = deleteRawBefore(rawBoundary);
        int aggregatesDeleted = deleteAggregatesBefore(aggregateBoundary);
        return new RetentionCounts(aggregated, rawDeleted, aggregatesDeleted);
    }

    private int aggregateCompletedDays(Instant completedBefore) {
        return jdbc.sql("""
                        insert into cp_heartbeat_daily(
                            instance_id, day, sample_count,
                            max_storage_used_bytes, max_active_users, max_outbox_pending,
                            last_app_version, last_schema_version)
                        select instance_id,
                               (received_at at time zone 'UTC')::date as day,
                               count(*) as sample_count,
                               max(storage_used_bytes) as max_storage_used_bytes,
                               max(active_users) as max_active_users,
                               max(outbox_pending) as max_outbox_pending,
                               (array_agg(app_version order by received_at desc, id desc))[1],
                               (array_agg(schema_version order by received_at desc, id desc))[1]
                        from cp_instance_heartbeats
                        where received_at < :completedBefore
                        group by instance_id, (received_at at time zone 'UTC')::date
                        on conflict (instance_id, day) do update set
                            sample_count = excluded.sample_count,
                            max_storage_used_bytes = excluded.max_storage_used_bytes,
                            max_active_users = excluded.max_active_users,
                            max_outbox_pending = excluded.max_outbox_pending,
                            last_app_version = excluded.last_app_version,
                            last_schema_version = excluded.last_schema_version
                        """)
                .param("completedBefore", completedBefore.atOffset(ZoneOffset.UTC))
                .update();
    }

    private int deleteRawBefore(Instant rawBoundary) {
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_DELETE_BATCHES; batch++) {
            int deleted = jdbc.sql("""
                        delete from cp_instance_heartbeats
                        where id in (
                            select id
                            from cp_instance_heartbeats
                            where received_at < :rawBoundary
                            order by received_at, id
                            limit :deleteBatchSize
                        )
                        """)
                .param("rawBoundary", rawBoundary.atOffset(ZoneOffset.UTC))
                .param("deleteBatchSize", deleteBatchSize)
                .update();
            totalDeleted += deleted;
            if (deleted < deleteBatchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private int deleteAggregatesBefore(LocalDate aggregateBoundary) {
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_DELETE_BATCHES; batch++) {
            int deleted = jdbc.sql("""
                        delete from cp_heartbeat_daily
                        where (instance_id, day) in (
                            select instance_id, day
                            from cp_heartbeat_daily
                            where day < :aggregateBoundary
                            order by day, instance_id
                            limit :deleteBatchSize
                        )
                        """)
                .param("aggregateBoundary", aggregateBoundary)
                .param("deleteBatchSize", deleteBatchSize)
                .update();
            totalDeleted += deleted;
            if (deleted < deleteBatchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private record RetentionCounts(int aggregated, int rawDeleted, int aggregatesDeleted) {
    }
}
