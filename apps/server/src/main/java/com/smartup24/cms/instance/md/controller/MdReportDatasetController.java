package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryMetaController.FieldMeta;
import com.smartup24.cms.instance.common.query.QueryMetaController.ListMeta;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.DatasetRequest;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/report-datasets} (ADR-0044): the datasets administrators define for reports — read with
 * {@code md.report_datasets.view}, kept with {@code md.report_datasets.manage}. A report names a dataset as
 * {@code ds.<code>} over its source entity; its reader needs no right on the dataset, only on the entity, and sees it
 * through their own scope and field rights.
 */
@RestController
@RequestMapping("/api/v1/report-datasets")
public class MdReportDatasetController {
    private static final String ONE = "/api/v1/report-datasets/{code}";

    private final MdReportDatasetService datasets;

    public MdReportDatasetController(MdReportDatasetService datasets) {
        this.datasets = datasets;
    }

    @Operation(
            summary = "List report datasets",
            description = "Requires md.report_datasets.view. The newest first; limit 1 to 100 (default 50, else 422).")
    @GetMapping
    @RequiresPermission(form = "md.report_datasets", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<Dataset> reportDatasets(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return datasets.list(limit, cursor);
    }

    @Operation(
            summary = "Describe the fields a dataset may offer",
            description = "Requires md.report_datasets.manage and the view right of the entity: its list fields and,"
                    + " with lines, each field of a line as line<Key>, as the administrator sees them.")
    @GetMapping("/fields")
    @RequiresPermission(form = "md.report_datasets", action = "manage")
    @ResponseStatus(HttpStatus.OK)
    public ListMeta reportDatasetFields(
            @RequestParam String entity, @RequestParam(required = false) @Nullable String lines) {
        var list = datasets.fields(entity, lines);
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
            summary = "Read a report dataset",
            description = "Requires md.report_datasets.view; its revision as ETag. 404 for an unknown dataset.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "md.report_datasets", action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Dataset> reportDataset(@PathVariable String code) {
        Dataset dataset = datasets.get(code);
        return ResponseEntity.ok().eTag(Revisions.etag(dataset.revision())).body(dataset);
    }

    @Operation(
            summary = "Create a report dataset",
            description =
                    "Requires md.report_datasets.manage. A permanent code, names in ru, uz and en, a source entity"
                            + " the administrator may view, optionally a table of its lines, 1 to 40 fields, a fixed filter of"
                            + " the list's DSL and up to 8 measures computed by formulas over aggregates. 409 for a taken code,"
                            + " 422 at the part to fix or above 100 datasets (8 per entity). Audited.")
    @PostMapping
    @RequiresPermission(form = "md.report_datasets", action = "manage")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    @NoIdempotency
    public ResponseEntity<Dataset> createReportDataset(@Valid @RequestBody DatasetRequest body) {
        Dataset dataset = datasets.create(body);
        ResponseEntity<Dataset> created = Created.at(ONE, dataset.code(), dataset);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .eTag(Revisions.etag(dataset.revision()))
                .body(dataset);
    }

    @Operation(
            summary = "Change a report dataset",
            description = "Requires md.report_datasets.manage and If-Match with its revision (428 without it, 409 when"
                    + " stale). The code never changes. Saved reports over fields it no longer offers are no longer"
                    + " offered to their readers. Audited.")
    @PutMapping("/{code}")
    @RequiresPermission(form = "md.report_datasets", action = "manage")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    @NoIdempotency
    public ResponseEntity<Dataset> updateReportDataset(
            @PathVariable String code,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody DatasetRequest body) {
        Dataset dataset = datasets.update(code, ifMatch, body);
        return ResponseEntity.ok().eTag(Revisions.etag(dataset.revision())).body(dataset);
    }

    @Operation(
            summary = "Delete a report dataset",
            description =
                    "Requires md.report_datasets.manage and If-Match with its revision (428/409). The saved reports"
                            + " over it are no longer offered; the records are untouched. Audited.")
    @DeleteMapping("/{code}")
    @RequiresPermission(form = "md.report_datasets", action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @NoIdempotency
    public void deleteReportDataset(
            @PathVariable String code,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        datasets.delete(code, ifMatch);
    }
}
