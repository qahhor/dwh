package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementDraftRequest;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementVersionRequest;
import com.smartup24.cms.instance.ms.notify.api.ManagedAnnouncementView;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsAnnouncementService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementAdminController {

    private final MsAnnouncementService service;

    public MsAnnouncementAdminController(MsAnnouncementService service) {
        this.service = service;
    }

    @GetMapping("/manage")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<List<ManagedAnnouncementView>> manage() {
        return ResponseEntity.ok(service.listAll());
    }

    @PostMapping
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ManagedAnnouncementView> create(@Valid @RequestBody AnnouncementDraftRequest request) {
        ManagedAnnouncementView created = service.create(request, currentUserId());
        return Created.at("/api/v1/announcements/{id}", created.id(), created);
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<ManagedAnnouncementView> update(
            @PathVariable("id") Long id, @Valid @RequestBody AnnouncementDraftRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "publish")
    public ResponseEntity<ManagedAnnouncementView> publish(
            @PathVariable("id") Long id, @Valid @RequestBody AnnouncementVersionRequest request) {
        return ResponseEntity.ok(service.publish(id, request.lockVersion()));
    }

    @PostMapping("/{id}/archive")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "archive")
    public ResponseEntity<ManagedAnnouncementView> archive(
            @PathVariable("id") Long id, @Valid @RequestBody AnnouncementVersionRequest request) {
        return ResponseEntity.ok(service.archive(id, request.lockVersion()));
    }

    private static Long currentUserId() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.notify.not_authenticated");
        }
        return userId;
    }
}
