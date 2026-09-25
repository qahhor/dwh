package com.greenwhite.dwh.instance.report.export;

import com.greenwhite.dwh.instance.fnd.jobs.FndJobHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Writes one queued export (ADR-0018); the export's own row records how it went. */
@Component
public class ReportExportJob implements FndJobHandler {

    private final ReportExportService exports;

    public ReportExportJob(ReportExportService exports) {
        this.exports = exports;
    }

    @Override
    public String code() {
        return ReportExportService.JOB;
    }

    @Override
    public void run(Map<String, Object> args) {
        exports.run(UUID.fromString(String.valueOf(args.get("exportId"))));
    }
}
