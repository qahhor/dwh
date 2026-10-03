package com.smartup24.cms.instance.jobs.api;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import java.util.Optional;

/**
 * The constraints of the job queue's tables {@code fnd_job_schedule}, {@code fnd_job_queue} and {@code fnd_job_runs}
 * (V100). Only the runner writes them, from values it builds itself, so no request meets them; they are listed so that
 * every constraint of the module has its code and text (ADR-0030).
 */
public enum JobError implements ConstraintCode {
    FND_JOB_SCHEDULE_CK_INTERVAL("fnd_job_schedule_ck_interval", ErrorCode.VALIDATION_FAILED),
    FND_JOB_QUEUE_FK_SCHEDULE("fnd_job_queue_fk_schedule", ErrorCode.CONFLICT),
    FND_JOB_RUNS_CK_STATUS("fnd_job_runs_ck_status", ErrorCode.VALIDATION_FAILED);

    private final String constraintName;
    private final ErrorCode errorCode;

    JobError(String constraintName, ErrorCode errorCode) {
        this.constraintName = constraintName;
        this.errorCode = errorCode;
    }

    @Override
    public String module() {
        return "jobs";
    }

    @Override
    public Optional<String> constraintName() {
        return Optional.of(constraintName);
    }

    @Override
    public ErrorCode errorCode() {
        return errorCode;
    }
}
