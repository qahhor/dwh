package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Removes the imports whose week is over, hourly by the schedule seeded in V187 (ADR-0032, 10.1). */
@Component
public class ReportImportCleanupJob implements JobHandler {

    public static final String CODE = "report.import_cleanup";

    private final ReportImportService imports;

    public ReportImportCleanupJob(ReportImportService imports) {
        this.imports = imports;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        imports.cleanup();
    }
}
