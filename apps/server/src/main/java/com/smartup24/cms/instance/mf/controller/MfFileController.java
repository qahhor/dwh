package com.smartup24.cms.instance.mf.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.mf.api.FileListItem;
import com.smartup24.cms.instance.mf.api.FileView;
import com.smartup24.cms.instance.mf.api.StorageStats;
import com.smartup24.cms.instance.mf.pref.MfPref;
import com.smartup24.cms.instance.mf.service.MfFileService;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class MfFileController {

    private final MfFileService fileService;

    public MfFileController(MfFileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "Upload a file", description = "Stores an uploaded file and returns its metadata.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(form = MfPref.FORM_FILES, action = "upload")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<FileView> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        Long currentUserId = SecurityContext.getCurrentUserId();

        var record = fileService.upload(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getInputStream(),
                file.getSize(),
                currentUserId);

        return Created.at("/api/v1/files/{id}", record.id(), record);
    }

    @Operation(summary = "Get storage statistics", description = "Usage of the file storage.")
    @GetMapping("/storage/stats")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<StorageStats> getStorageStats() {
        Long currentUserId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(fileService.getStorageStats(currentUserId));
    }

    @Operation(summary = "List files", description = "The stored files the caller may see.")
    @GetMapping
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<KeysetPage<FileListItem>> listFiles(
            @RequestParam(name = "scope", defaultValue = "all") String scope,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        boolean onlyMine = "mine".equalsIgnoreCase(scope);
        return ResponseEntity.ok(fileService.listFiles(currentUserId, onlyMine, limit, cursor, filter, sort, query));
    }

    @Operation(summary = "Delete a file", description = "Removes a stored file.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteFile(@PathVariable("id") UUID id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        boolean canDeleteAny = SecurityContext.hasPermission(MfPref.FORM_FILES, "manage_quotas");
        fileService.deleteFile(id, currentUserId, canDeleteAny);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get file metadata", description = "The name, type, size and owner of a stored file.")
    @GetMapping("/{id}")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<FileView> getFileMetadata(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(fileService.getFileMetadata(id, SecurityContext.getCurrentUserId()));
    }

    @Operation(summary = "Download a file", description = "The content of a stored file.")
    @GetMapping("/{id}/download")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable("id") UUID id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        var metadata = fileService.getFileMetadata(id, currentUserId);
        var stream = fileService.downloadFile(id, currentUserId);

        String encodedFilename = URLEncoder.encode(metadata.originalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(metadata.mimeType()))
                .contentLength(metadata.sizeBytes())
                .body(new InputStreamResource(stream.inputStream()));
    }
}
