package com.greenwhite.dwh.instance.report.export;

import com.greenwhite.dwh.instance.fnd.jobs.FndJobHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Removes exports whose week is over, hourly by the schedule seeded in V119 (ADR-0018). */
@Component
public class ReportExportCleanupJob implements FndJobHandler {

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
