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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    @ResponseStatus(HttpStatus.OK)
    public ReportDatasetDtos.Catalog datasets(@PathVariable String code) {
        return datasets.catalog(code);
    }

    @Operation(
            summary = "Describe the authorized report source",
            description = "Returns current visible fields and report capabilities for the entity or a named dataset.")
    @GetMapping("/{code}/report-meta")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
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
                    + " groups, ordered by their values, their words or a measure and cut to a top when asked; each"
                    + " row names its group values in the viewer's language and scope. A measure may count different"
                    + " values or show its share of a total; a pivot lays the two groupings out as rows and columns"
                    + " (at most 50 columns and 10000 cells) with totals; a comparison answers each measure over the"
                    + " current and an earlier period with the change.")
    @GetMapping("/{code}/report")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
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
            @RequestParam(required = false) @Nullable String dataset,
            @Parameter(
                            description = "JSON object: the order of the groups - by group (their values, the default),"
                                    + " label (their words) or measure (by position):"
                                    + " {\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"}")
                    @RequestParam(required = false)
                    @Nullable
                    String sort,
            @Parameter(description = "The most groups kept, 1 to 1000, in the order asked for")
                    @RequestParam(required = false)
                    @Nullable
                    Integer limit,
            @Parameter(
                            description =
                                    "A pivot (ADR-0044): the first grouping makes the rows, the second the columns,"
                                            + " with totals of rows, columns and the whole")
                    @RequestParam(required = false)
                    @Nullable
                    Boolean pivot,
            @Parameter(description = "The totals of the groups (ADR-0044)") @RequestParam(required = false) @Nullable
                    Boolean totals,
            @Parameter(
                            description = "JSON object: the period comparison (ADR-0044) -"
                                    + " {\"field\":\"date\",\"period\":\"month\",\"against\":\"previous\"};"
                                    + " period month, quarter, year, ytd or custom (with from and to), against previous"
                                    + " or year; periods in the X-Time-Zone of the request")
                    @RequestParam(required = false)
                    @Nullable
                    String compare) {
        return ResponseEntity.ok(reports.execute(
                        code,
                        dataset,
                        new EntityReports.Request(groupBy, measures, filter, sort, limit, pivot, totals, compare))
                .data());
    }

    @Operation(
            summary = "Run a saved report",
            description = "Runs an owned or role-shared report or widget using the current reader's data and field"
                    + " access, narrowed by the values of its parameters.")
    @GetMapping("/{code}/reports/{viewId}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<QueryAggregateResult> saved(
            @PathVariable String code,
            @PathVariable long viewId,
            @Parameter(
                            description = "JSON object: the values of the report's parameters (ADR-0044) by key -"
                                    + " {\"period\":{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"},"
                                    + "\"region\":[\"north\"]}; a period may name a preset"
                                    + " ({\"preset\":\"this_month\"}); a missing value takes its default")
                    @RequestParam(required = false)
                    @Nullable
                    String parameters) {
        return ResponseEntity.ok(
                reports.executeSaved(code, viewId, null, parameters).data());
    }
}
