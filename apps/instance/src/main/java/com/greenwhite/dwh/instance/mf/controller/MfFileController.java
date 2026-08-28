package com.greenwhite.dwh.instance.mf.controller;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.mf.pref.MfPref;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class MfFileController {

    private final MfFileService fileService;

    public MfFileController(MfFileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(form = MfPref.FORM_FILES, action = "upload")
    public ResponseEntity<MfFileRepository.FileRecord> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        Long currentUserId = SecurityContext.getCurrentUserId();

        var record = fileService.uploadFile(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getInputStream(),
                file.getSize(),
                currentUserId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<MfFileRepository.FileRecord> getFileMetadata(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(fileService.getFileMetadata(id));
    }

    @GetMapping("/{id}/download")
    @RequiresPermission(form = MfPref.FORM_FILES, action = "view")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable("id") UUID id) {
        var metadata = fileService.getFileMetadata(id);
        var stream = fileService.downloadFile(id);

        String encodedFilename = URLEncoder.encode(metadata.originalName(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(metadata.mimeType()))
                .contentLength(metadata.sizeBytes())
                .body(new InputStreamResource(stream.inputStream()));
    }
}
