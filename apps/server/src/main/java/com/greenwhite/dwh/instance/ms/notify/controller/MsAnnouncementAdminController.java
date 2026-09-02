package com.greenwhite.dwh.instance.ms.notify.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementDraftRequest;
import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import com.greenwhite.dwh.instance.ms.notify.repository.MsAnnouncementRepository;
import com.greenwhite.dwh.instance.ms.notify.service.MsAnnouncementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementAdminController {

    private final MsAnnouncementService service;

    public MsAnnouncementAdminController(MsAnnouncementService service) {
        this.service = service;
    }

    @GetMapping("/manage")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<List<MsAnnouncementRepository.ManagedAnnouncementRecord>> manage() {
        return ResponseEntity.ok(service.listAll());
    }

    @PostMapping
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "create")
    public ResponseEntity<MsAnnouncementRepository.ManagedAnnouncementRecord> create(
            @Valid @RequestBody AnnouncementDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, currentUserId()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<MsAnnouncementRepository.ManagedAnnouncementRecord> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody AnnouncementDraftRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "publish")
    public ResponseEntity<MsAnnouncementRepository.ManagedAnnouncementRecord> publish(
            @PathVariable("id") Long id,
            @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.publish(id, request.lockVersion()));
    }

    @PostMapping("/{id}/archive")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "archive")
    public ResponseEntity<MsAnnouncementRepository.ManagedAnnouncementRecord> archive(
            @PathVariable("id") Long id,
            @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.archive(id, request.lockVersion()));
    }

    private static Long currentUserId() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }
        return userId;
    }

    public record VersionRequest(@NotNull @PositiveOrZero Long lockVersion) {
    }
}
