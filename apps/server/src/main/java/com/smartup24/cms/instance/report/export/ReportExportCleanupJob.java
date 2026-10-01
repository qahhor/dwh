package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Removes exports whose week is over, hourly by the schedule seeded in V119 (ADR-0018). */
@Component
public class ReportExportCleanupJob implements JobHandler {

    public static final String CODE = "report.export_cleanup";

    private final ReportExportService exports;

    public ReportExportCleanupJob(ReportExportService exports) {
        this.exports = exports;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        exports.cleanup();
    }
}
