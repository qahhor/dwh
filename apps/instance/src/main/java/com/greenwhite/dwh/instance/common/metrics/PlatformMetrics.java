package com.greenwhite.dwh.instance.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PlatformMetrics {

    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;
    private final Counter rateLimitCounter;
    private final Counter taskCreatedCounter;
    private final Counter taskStatusChangedCounter;
    private final Counter fileUploadedBytesCounter;
    private final Counter auditMutationCounter;

    public PlatformMetrics(MeterRegistry meterRegistry) {
        this.loginSuccessCounter = Counter.builder("dwh_auth_logins_total")
                .tag("status", "success")
                .description("Total number of successful logins")
                .register(meterRegistry);

        this.loginFailureCounter = Counter.builder("dwh_auth_logins_total")
                .tag("status", "failure")
                .description("Total number of failed login attempts")
                .register(meterRegistry);

        this.rateLimitCounter = Counter.builder("dwh_security_rate_limit_exceeded_total")
                .description("Total number of rate limit exceeded events")
                .register(meterRegistry);

        this.taskCreatedCounter = Counter.builder("dwh_tasks_created_total")
                .description("Total number of created tasks")
                .register(meterRegistry);

        this.taskStatusChangedCounter = Counter.builder("dwh_tasks_status_changed_total")
                .description("Total number of task status transitions")
                .register(meterRegistry);

        this.fileUploadedBytesCounter = Counter.builder("dwh_files_uploaded_bytes_total")
                .description("Total number of bytes uploaded")
                .baseUnit("bytes")
                .register(meterRegistry);

        this.auditMutationCounter = Counter.builder("dwh_audit_mutations_total")
                .description("Total number of audited data mutations")
                .register(meterRegistry);
    }

    public void incrementLoginSuccess() {
        loginSuccessCounter.increment();
    }

    public void incrementLoginFailure() {
        loginFailureCounter.increment();
    }

    public void incrementRateLimit() {
        rateLimitCounter.increment();
    }

    public void incrementTaskCreated() {
        taskCreatedCounter.increment();
    }

    public void incrementTaskStatusChanged() {
        taskStatusChangedCounter.increment();
    }

    public void incrementFileUploadedBytes(long bytes) {
        fileUploadedBytesCounter.increment(bytes);
    }

    public void incrementAuditMutation() {
        auditMutationCounter.increment();
    }
}
