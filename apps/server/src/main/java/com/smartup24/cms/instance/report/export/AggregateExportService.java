package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.entity.report.EntityDashboardReports;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.md.service.MdI18nService;
import java.io.IOException;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every bounded download executes a freshly authorized report; no raw-row job or cached file (ADR-0036). */
@Service
@Transactional(readOnly = true)
public class AggregateExportService {
    private final EntityReports reports;
    private final EntityDashboardReports dashboards;
    private final MdI18nService i18n;

    public AggregateExportService(EntityReports reports, EntityDashboardReports dashboards, MdI18nService i18n) {
        this.reports = reports;
        this.dashboards = dashboards;
        this.i18n = i18n;
    }

    public byte[] report(
            String code,
            @Nullable String dataset,
            @Nullable String groupBy,
            @Nullable String measures,
            @Nullable String filter,
            String lang)
            throws IOException {
        var result = reports.execute(code, dataset, groupBy, measures, filter);
        return workbook(code, result.list(), result.data(), lang);
    }

    public byte[] saved(String code, long viewId, String lang) throws IOException {
        var result = reports.executeSaved(code, viewId, null);
        return workbook(code, result.list(), result.data(), lang);
    }

    public byte[] dashboard(
            long id, long revision, long viewId, @Nullable LocalDate from, @Nullable LocalDate to, String lang)
            throws IOException {
        var result = dashboards.result(id, revision, viewId, from, to);
        return workbook(result.entity(), result.list(), result.data(), lang);
    }

    private byte[] workbook(String code, QueryList list, QueryAggregateResult result, String lang) throws IOException {
        var dictionary = i18n.effectiveDictionary(lang);
        return AggregateWorkbookWriter.write(code, list, result, key -> dictionary.getOrDefault(key, key));
    }
}
