package com.smartup24.cms.instance.config.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

/** Plan 10/10, item 3.13: settings and failures of the retention job. */
class RetentionJobTest {

    private static final RetentionPolicy LOGS = new RetentionPolicy("probe-logs", "probe_logs", "at < :cutoff", 30);

    @Test
    @DisplayName("3.13: 0 days keeps a journal whole; the job never touches it")
    void zeroDaysKeepsTheRows() {
        JdbcClient jdbc = mock(JdbcClient.class);
        RetentionJob job = job(jdbc, new MockEnvironment().withProperty("smc.retention.days.probe-logs", "0"));

        assertThat(job.run()).isEmpty();
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    @DisplayName("3.13: a journal that cannot be cleaned is logged and the run goes on")
    void failureIsReportedAndTheRunGoesOn() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("table is locked"));
        RetentionJob job = job(jdbc, new MockEnvironment());

        assertThat(job.run()).isEmpty();
        job.runScheduled();
    }

    @Test
    @DisplayName("3.13: a policy names a table and uses the cutoff")
    void policyIsChecked() {
        for (Runnable bad : List.<Runnable>of(
                () -> new RetentionPolicy("Bad Name", "t", "at < :cutoff", 1),
                () -> new RetentionPolicy("ok", "t; drop", "at < :cutoff", 1),
                () -> new RetentionPolicy("ok", "t", "at < now()", 1),
                () -> new RetentionPolicy("ok", "t", "at < :cutoff", -1))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static RetentionJob job(JdbcClient jdbc, MockEnvironment environment) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters =
                beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);
        ObjectProvider<Clock> clock = beans.getBeanProvider(Clock.class);
        return new RetentionJob(jdbc, List.of(LOGS), environment, meters, clock);
    }
}
