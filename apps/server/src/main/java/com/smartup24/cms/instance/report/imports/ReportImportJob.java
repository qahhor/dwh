package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.jobs.api.JobAttempt;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Runs one queued import (ADR-0032, 10.1); the import's own row records how it went. */
@Component
public class ReportImportJob implements JobHandler {

    private final ReportImportService imports;

    public ReportImportJob(ReportImportService imports) {
        this.imports = imports;
    }

    @Override
    public String code() {
        return ReportImportService.JOB;
    }

    @Override
    public void run(Map<String, Object> args) {
        run(args, JobAttempt.only());
    }

    @Override
    public void run(Map<String, Object> args, JobAttempt attempt) {
        imports.run(UUID.fromString(String.valueOf(args.get("importId"))), attempt);
    }
}
