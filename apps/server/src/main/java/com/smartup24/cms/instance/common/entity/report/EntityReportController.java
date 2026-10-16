package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.api.ReportDatasetDtos;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryMetaController.FieldMeta;
import com.smartup24.cms.instance.common.query.QueryMetaController.ListMeta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/entities/{code}/report} and {@code /reports/{viewId}} (ADR-0032, 10.2; plan 10/10, item 5.8): the
 * totals of an entity's list grouped as a report asks — the report builder's request, or a report or widget the viewer
 * saved as a view of the list. Open to whoever is signed in; the entity's own {@code view} right, its data scope and
 * its field rights are checked by {@link EntityReports}, as for its list.
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityReportController {

    private final EntityReports reports;
    private final EntityReportDatasets datasets;

    public EntityReportController(EntityReports reports, EntityReportDatasets datasets) {
        this.reports = reports;
        this.datasets = datasets;
    }

    @Operation(
            summary = "List available prepared report datasets",
            description = "Lists up to 16 trusted datasets after checking root, reference and target access.")
    @GetMapping("/{code}/report-datasets")
    @RequiresPermission(form = "md.profile", action = "view")
    public ReportDatasetDtos.Catalog datasets(@PathVariable String code) {
        return datasets.catalog(code);
    }

    @Operation(
            summary = "Describe the authorized report source",
            description = "Returns current visible fields and report capabilities for the entity or a named dataset.")
    @GetMapping("/{code}/report-meta")
    @RequiresPermission(form = "md.profile", action = "view")
    public ListMeta metadata(@PathVariable String code, @RequestParam(required = false) @Nullable String dataset) {
        var list = reports.list(code, dataset);
        return new ListMeta(
                list.code(),
                list.viewerFields().stream().map(FieldMeta::of).toList(),
                (list.defaultDescending() ? "-" : "") + list.defaultSort(),
                list.defaultLimit(),
                list.maxLimit(),
                QueryCompiler.MAX_CONDITIONS,
                QueryCompiler.MAX_IN_VALUES);
    }

    @Operation(
            summary = "Run a report over a list",
            description = "Groups the records of an entity the viewer may see and answers counts and totals per group:"
                    + " at most 2 groupings (a choice, a yes/no, a reference or a date bucket), 4 measures and 1000"
                    + " groups.")
    @GetMapping("/{code}/report")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<QueryAggregateResult> report(
            @PathVariable String code,
            @Parameter(
                            description =
                                    "JSON array: [{\"field\":\"status\"},{\"field\":\"createdAt\",\"trunc\":\"month\"}]")
                    @RequestParam(required = false)
                    @Nullable
                    String groupBy,
            @Parameter(description = "JSON array: [{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"total\"}]")
                    @RequestParam(required = false)
                    @Nullable
                    String measures,
            @Parameter(description = "The list's filter DSL (JSON array)") @RequestParam(required = false) @Nullable
                    String filter,
            @RequestParam(required = false) @Nullable String dataset) {
        return ResponseEntity.ok(
                reports.execute(code, dataset, groupBy, measures, filter).data());
    }

    @Operation(
            summary = "Run a saved report",
            description =
                    "Runs an owned or role-shared report or widget using the current reader's data and field access.")
    @GetMapping("/{code}/reports/{viewId}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<QueryAggregateResult> saved(@PathVariable String code, @PathVariable long viewId) {
        return ResponseEntity.ok(reports.saved(code, viewId));
    }
}
