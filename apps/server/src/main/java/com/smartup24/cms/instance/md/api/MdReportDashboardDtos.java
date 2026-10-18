package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Shared report preferences and named dashboards; neither is an activated publication package (ADR-0036). */
public final class MdReportDashboardDtos {
    private MdReportDashboardDtos() {}

    public record ReportViewItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entity,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String listCode,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"report", "widget"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            JsonNode state,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int lockVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canEdit,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canShare,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean shared,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The reader owns the report; otherwise a role shares it with them")
            boolean owned) {}

    /**
     * What narrows the reports catalog: a list code, a part of the name (any case), {@code mine} or {@code shared},
     * {@code report} or {@code widget}; null narrows nothing.
     */
    public record ReportCatalogFilter(
            @Nullable String listCode,
            @Nullable String q,
            @Nullable String owner,
            @Nullable String kind) {}

    public record SharingRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<Long> roleIds) {}

    public record SharingResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<Long> roleIds) {}

    public record SharingRole(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardWidgetRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long viewId,

            @Nullable String periodField,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2")
            int width) {}

    public record DashboardRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 80)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<DashboardWidgetRequest> widgets) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardWidget(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long viewId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2")
            int width,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean available,

            @Nullable String periodField,
            @Nullable ReportViewItem report) {}

    public record DashboardSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canEdit,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canShare,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean shared,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int widgetCount) {}

    public record DashboardDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canEdit,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canShare,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean shared,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int widgetCount,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<DashboardWidget> widgets) {}
}
