package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** The dashboard period is an explicit additional predicate, never a replacement of a report filter (ADR-0036). */
@Component
public class MdDashboardPeriod {
    private final EntityReports reports;
    private final ObjectMapper json;

    public MdDashboardPeriod(EntityReports reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    public QueryField field(String entity, @Nullable String dataset, String key) {
        return reports.list(entity, dataset)
                .viewerField(key)
                .filter(QueryField::filterable)
                .filter(field -> field.type() == QueryFieldType.DATE || field.type() == QueryFieldType.INSTANT)
                .orElseThrow(() -> invalid("periodField", "error.md.dashboard_period_field_invalid"));
    }

    public @Nullable String filter(
            String entity,
            @Nullable String dataset,
            @Nullable String key,
            @Nullable LocalDate from,
            @Nullable LocalDate to) {
        if (from == null && to == null) return null;
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1 || to.getYear() > 9999) {
            throw invalid("from", "error.md.dashboard_period_invalid");
        }
        if (key == null) throw invalid("periodField", "error.md.dashboard_period_field_required");
        QueryField field = field(entity, dataset, key);
        ArrayNode filter = json.createArrayNode();
        if (field.type() == QueryFieldType.DATE) {
            filter.addObject()
                    .put("field", key)
                    .put("op", "between")
                    .putArray("value")
                    .add(from.toString())
                    .add(to.toString());
        } else {
            if (to.getYear() == 9999 && to.getMonthValue() == 12 && to.getDayOfMonth() == 31) {
                throw invalid("to", "error.md.dashboard_period_invalid");
            }
            filter.addObject()
                    .put("field", key)
                    .put("op", "gte")
                    .put("value", from.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
            filter.addObject()
                    .put("field", key)
                    .put("op", "lt")
                    .put(
                            "value",
                            to.plusDays(1)
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant()
                                    .toString());
        }
        return filter.toString();
    }

    private static ApiException invalid(String at, String key) {
        return ApiException.validation(
                "error.md.report_preferences_invalid",
                List.of(FieldErrorItem.keyed(at, "DASHBOARD_PERIOD_INVALID", key)));
    }
}
