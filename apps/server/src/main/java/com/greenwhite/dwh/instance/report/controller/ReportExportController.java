package com.greenwhite.dwh.instance.report.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.report.export.ReportExportService;
import com.greenwhite.dwh.instance.report.export.ReportExportService.ExportRequest;
import com.greenwhite.dwh.instance.report.repository.ReportExportRepository.ExportRow;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    /** One export in the journal; the file is there while {@code state} is {@code done} and it has not expired. */
    public record ExportItem(UUID id, String list, String state, Integer rowsCount, boolean truncated, String fileName,
                             Long sizeBytes, String errorCode, Instant createdAt, Instant finishedAt, Instant expiresAt) {

        static ExportItem of(ExportRow row) {
            return new ExportItem(row.publicId(), row.listCode(), row.state(), row.rowsCount(), row.truncated(),
                    row.fileName(), row.sizeBytes(), row.errorCode(), row.createdAt(), row.finishedAt(), row.expiresAt());
        }
    }

    @PostMapping
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<ExportItem> request(@RequestBody ExportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ExportItem.of(exports.request(request)));
    }

    @GetMapping
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<List<ExportItem>> journal() {
        return ResponseEntity.ok(exports.journal().stream().map(ExportItem::of).toList());
    }

    @GetMapping("/{id}/file")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<InputStreamResource> file(@PathVariable String id) {
        ReportExportService.ExportFile file = exports.file(id);
        var response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString());
        if (file.sizeBytes() >= 0) {
            response.contentLength(file.sizeBytes());
        }
        return response.body(new InputStreamResource(file.content()));
    }
}
