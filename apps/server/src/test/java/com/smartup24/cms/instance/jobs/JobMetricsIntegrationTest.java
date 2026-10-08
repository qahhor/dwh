package com.smartup24.cms.instance.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.jobs.repository.JobQueueRepository;
import com.smartup24.cms.instance.jobs.repository.JobQueueRepository.QueueState;
import com.smartup24.cms.instance.jobs.runner.JobMetrics;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan 10/10, item 7.3: the job queue gauges count the due, unleased jobs, the wait of the oldest past its time and
 * the jobs out of attempts. The rows are inserted inside the test transaction and rolled back.
 */
@Transactional
class JobMetricsIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobQueueRepository queue;

    @Autowired
    JobMetrics metrics;

    @Autowired
    MeterRegistry registry;

    @Test
    @DisplayName("7.3: due and failed jobs and the lag of the oldest due job reach the gauges")
    void sampleReadsTheQueue() {
        QueueState before = queue.state();
        insert("now() - interval '10 minutes'", null, null);
        // Leased by a live node: running, not waiting.
        insert("now() - interval '1 hour'", "now() + interval '5 minutes'", null);
        // Not due yet.
        insert("now() + interval '1 hour'", null, null);
        insert("now() - interval '2 days'", null, "now()");

        metrics.sample();

        assertThat(gauge("smc.jobs.queue.due")).isEqualTo(before.due() + 1.0);
        assertThat(gauge("smc.jobs.queue.failed")).isEqualTo(before.failed() + 1.0);
        assertThat(gauge("smc.jobs.queue.lag")).isGreaterThanOrEqualTo(600.0);
    }

    @Test
    @DisplayName("7.3: each attempt is timed by handler and outcome")
    void attemptsAreTimed() {
        metrics.executed("test.attempts", 2_000_000, true);
        metrics.executed("test.attempts", 3_000_000, false);

        assertThat(registry.get("smc.jobs.execution")
                        .tags("handler", "test.attempts", "outcome", "success")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("smc.jobs.execution")
                        .tags("handler", "test.attempts", "outcome", "failure")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    private void insert(String nextRunAt, String lockedUntil, String failedAt) {
        jdbc.sql("insert into fnd_job_queue (handler, next_run_at, locked_until, failed_at) values ('test.metrics', "
                        + nextRunAt + ", " + lockedUntil + ", " + failedAt + ")")
                .update();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
