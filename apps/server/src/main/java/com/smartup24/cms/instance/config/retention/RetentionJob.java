package com.smartup24.cms.instance.config.retention;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the rows of journal tables past their retention (plan 10/10, item 3.13, docs/ops/operations-runbook.md).
 * Each policy is emptied in short batches, each its own statement and transaction, so the job never holds a long
 * transaction or a lock on a table the application writes; {@code for update skip locked} lets two nodes run it at
 * the same time without waiting on each other. A run stops after {@code maxBatches} per policy and continues on the
 * next run, so a first run on a large backlog stays bounded.
 *
 * <p>Rows are picked by {@code ctid}, which is unique within one table only: the job refuses a partitioned table
 * (ADR-0025), at startup and again before each purge, since one delete would reach the rows of every partition that
 * share a {@code ctid}.
 */
@Component
@Profile("!migrate")
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    static final String DAYS = "smc.retention.days.";

    private final JdbcClient jdbc;
    private final List<RetentionPolicy> policies;
    private final Environment environment;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;
    private final Set<String> regularTables = ConcurrentHashMap.newKeySet();

    public RetentionJob(
            JdbcClient jdbc,
            List<RetentionPolicy> policies,
            Environment environment,
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.policies = List.copyOf(policies);
        this.environment = environment;
        this.meters = meters;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.batchSize = environment.getProperty("smc.retention.batch-size", Integer.class, 5_000);
        this.maxBatches = environment.getProperty("smc.retention.max-batches", Integer.class, 200);
    }

    /** Fails the start when a policy names a partitioned table; an unreachable database leaves it to each run. */
    @PostConstruct
    void refusePartitionedTables() {
        List<String> partitioned;
        try {
            partitioned =
                    partitioned(policies.stream().map(RetentionPolicy::table).toList());
        } catch (DataAccessException unreachable) {
            log.warn("retention_table_check_deferred error={}", unreachable.toString());
            return;
        }
        if (!partitioned.isEmpty()) {
            throw new IllegalStateException("Retention policies name partitioned tables " + partitioned
                    + ": rows are deleted by ctid, which is unique within one partition only (ADR-0025)");
        }
    }

    @Scheduled(cron = "${smc.retention.cron:0 30 3 * * *}")
    public void runScheduled() {
        run();
    }

    /** One pass over every policy: the rows deleted, by policy name. */
    public Map<String, Long> run() {
        Map<String, Long> deleted = new LinkedHashMap<>();
        for (RetentionPolicy policy : policies) {
            try {
                // Read inside the try: one bad setting must not stop the other journals.
                int days = environment.getProperty(DAYS + policy.name(), Integer.class, policy.defaultDays());
                if (days <= 0) {
                    continue;
                }
                requireRegularTable(policy);
                deleted.put(policy.name(), purge(policy, clock.instant().minus(Duration.ofDays(days))));
            } catch (RuntimeException failure) {
                // One journal that cannot be cleaned must not keep the others growing; the next run retries it.
                log.warn("retention_failed policy={} table={}", policy.name(), policy.table(), failure);
            }
        }
        return deleted;
    }

    private void requireRegularTable(RetentionPolicy policy) {
        if (regularTables.contains(policy.table())) {
            return;
        }
        if (!partitioned(List.of(policy.table())).isEmpty()) {
            throw new IllegalStateException("Retention policy " + policy.name() + ": table " + policy.table()
                    + " is partitioned; rows are deleted by ctid, which is unique within one partition only");
        }
        regularTables.add(policy.table());
    }

    /** The tables of {@code tables} that are partitioned, in the current schema. */
    private List<String> partitioned(List<String> tables) {
        if (tables.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                        + " where n.nspname = current_schema() and c.relkind = 'p' and c.relname = any(:tables)"
                        + " order by c.relname")
                .param("tables", tables.toArray(String[]::new))
                .query(String.class)
                .list();
    }

    private long purge(RetentionPolicy policy, Instant cutoff) {
        long started = System.nanoTime();
        String sql = "delete from " + policy.table() + " where ctid = any(array(select ctid from " + policy.table()
                + " where " + policy.expired() + " limit :batch for update skip locked))";
        long total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int rows = jdbc.sql(sql)
                    .param("cutoff", Timestamp.from(cutoff))
                    .param("batch", batchSize)
                    .update();
            total += rows;
            if (rows < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info(
                    "retention_purged policy={} table={} rows={} cutoff={} millis={}",
                    policy.name(),
                    policy.table(),
                    total,
                    cutoff,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            long counted = total;
            meters.ifAvailable(registry -> Counter.builder("smc_retention_deleted_rows_total")
                    .description("Rows of journal tables deleted past their retention (plan item 3.13)")
                    .tag("policy", policy.name())
                    .register(registry)
                    .increment(counted));
        }
        return total;
    }
}
