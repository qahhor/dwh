package com.smartup24.cms.instance.report.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.report.api.ImportRequest;
import com.smartup24.cms.instance.report.api.ImportView;
import com.smartup24.cms.instance.report.imports.ReportImportService;
import com.smartup24.cms.instance.report.imports.ReportImportService.ImportFileContent;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The import of entity records (ADR-0032, 10.1; plan 10/10, item 5.8): the template of an entity, the start of an
 * import of an uploaded file and the person's view of it. Any signed-in person may ask; the service requires the
 * entity's {@code view} and {@code import}, as the runtime does (ADR-0028), and each person sees only their imports.
 */
@RestController
public class ReportImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportImportService imports;

    public ReportImportController(ReportImportService imports) {
        this.imports = imports;
    }

    @Operation(
            operationId = "importTemplate",
            summary = "Download an import template",
            description = "An xlsx file with a column per field of the entity the caller may write; the second, hidden"
                    + " row names the fields by key, a hint sheet lists the values of each choice.")
    @GetMapping("/api/v1/entities/{code}/import-template")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<InputStreamResource> template(
            @PathVariable String code, @RequestParam(required = false) String lang) {
        return file(imports.template(code, lang));
    }

    @Operation(
            operationId = "startImport",
            summary = "Start an import",
            description = "Queues the import of an xlsx file the caller uploaded: a dry run checks every row and writes"
                    + " nothing, apply creates or changes records by the entity's import key.")
    @PostMapping("/api/v1/entities/{code}/imports")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ImportView> start(@PathVariable String code, @RequestBody ImportRequest request) {
        ImportView started = imports.request(code, request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/imports/" + started.id()))
                .body(started);
    }

    @Operation(
            operationId = "getImport",
            summary = "Get an import",
            description = "The caller's import: its state, counters and first problems by row.")
    @GetMapping("/api/v1/imports/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<ImportView> get(@PathVariable String id) {
        return ResponseEntity.ok(imports.get(id));
    }

    @Operation(
            operationId = "importReport",
            summary = "Download an import report",
            description = "The file of a finished import with a column that lists the problems of each refused row.")
    @GetMapping("/api/v1/imports/{id}/report")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<InputStreamResource> report(@PathVariable String id) {
        return file(imports.report(id));
    }

    private static ResponseEntity<InputStreamResource> file(ImportFileContent file) {
        var response = ResponseEntity.ok()
                .contentType(XLSX)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString());
        if (file.sizeBytes() >= 0) response.contentLength(file.sizeBytes());
        return response.body(new InputStreamResource(file.content()));
    }
}
