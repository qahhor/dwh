package com.smartup24.cms.instance.report.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.report.export.AggregateExportService;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** XLSX of the same aggregate results and permissions as the table, limited to 1000 groups (ADR-0036). */
@RestController
@RequestMapping("/api/v1")
public class AggregateExportController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final AggregateExportService exports;

    public AggregateExportController(AggregateExportService exports) {
        this.exports = exports;
    }

    @Operation(
            summary = "Export aggregate report rows",
            description =
                    "Exports the report's original groups and measures in its order and top, its group values named"
                            + " in the export language, with currency, UTC buckets and truncation metadata; a pivot as"
                            + " its matrix with the totals of rows, columns and the whole; a comparison with its periods."
                            + " Does not export source records or chart series reductions.")
    @GetMapping(value = "/entities/{code}/report/export", produces = XLSX)
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<byte[]> report(
            @PathVariable String code,
            @RequestParam(required = false) @Nullable String groupBy,
            @RequestParam(required = false) @Nullable String measures,
            @RequestParam(required = false) @Nullable String filter,
            @RequestParam(required = false) @Nullable String dataset,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable Boolean pivot,
            @RequestParam(required = false) @Nullable Boolean totals,
            @RequestParam(required = false) @Nullable String compare,
            @RequestParam(defaultValue = "ru") String lang)
            throws IOException {
        return file(exports.report(
                code,
                dataset,
                new EntityReports.Request(groupBy, measures, filter, sort, limit, pivot, totals, compare),
                lang));
    }

    @Operation(
            summary = "Export a saved report",
            description = "Rechecks current source sharing and the reader's data and field rights.")
    @GetMapping(value = "/entities/{code}/reports/{viewId}/export", produces = XLSX)
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<byte[]> saved(
            @PathVariable String code,
            @PathVariable long viewId,
            @RequestParam(required = false) @Nullable String parameters,
            @RequestParam(defaultValue = "ru") String lang)
            throws IOException {
        return file(exports.saved(code, viewId, parameters, lang));
    }

    @Operation(
            summary = "Export a dashboard widget",
            description = "Requires the current dashboard revision in If-Match."
                    + " Uses its source and UTC period mapping with the same authorization as the widget result.")
    @GetMapping(value = "/dashboards/{id}/widgets/{viewId}/export", produces = XLSX)
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<byte[]> dashboard(
            @PathVariable long id,
            @PathVariable long viewId,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(defaultValue = "ru") String lang)
            throws IOException {
        return file(exports.dashboard(id, Revisions.required(ifMatch), viewId, from, to, lang));
    }

    private static ResponseEntity<byte[]> file(byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("report.xlsx")
                                .build()
                                .toString())
                .contentLength(content.length)
                .body(content);
    }
}
