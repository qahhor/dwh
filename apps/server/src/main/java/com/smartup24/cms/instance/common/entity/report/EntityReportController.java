package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
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

    public EntityReportController(EntityReports reports) {
        this.reports = reports;
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
                    String filter) {
        return ResponseEntity.ok(reports.run(code, groupBy, measures, filter));
    }

    @Operation(
            summary = "Run a saved report",
            description = "Runs a report or widget the viewer saved as a view of the entity's list.")
    @GetMapping("/{code}/reports/{viewId}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<QueryAggregateResult> saved(@PathVariable String code, @PathVariable long viewId) {
        return ResponseEntity.ok(reports.saved(code, viewId));
    }
}
