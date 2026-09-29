package com.smartup24.cms.instance.report.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.report.api.ExportItem;
import com.smartup24.cms.instance.report.api.ExportRequest;
import com.smartup24.cms.instance.report.export.ReportExportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exports of registry lists and the person's journal of them (ADR-0018). Any
 * signed-in person may ask; the service requires the list's own right, as
 * query-meta does, and each person sees and downloads only their exports.
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ReportExportController {

    private final ReportExportService exports;

    public ReportExportController(ReportExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<ExportItem> request(@RequestBody ExportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(exports.request(request));
    }

    @GetMapping
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<List<ExportItem>> journal() {
        return ResponseEntity.ok(exports.journal());
    }

    @GetMapping("/{id}/file")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<InputStreamResource> file(@PathVariable String id) {
        ReportExportService.ExportFile file = exports.file(id);
        var response = ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString());
        if (file.sizeBytes() >= 0) {
            response.contentLength(file.sizeBytes());
        }
        return response.body(new InputStreamResource(file.content()));
    }
}
