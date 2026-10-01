package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Writes one queued export (ADR-0018); the export's own row records how it went. */
@Component
public class ReportExportJob implements JobHandler {

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
