package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementDraftRequest;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementVersionRequest;
import com.smartup24.cms.instance.ms.notify.api.ManagedAnnouncementView;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsAnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementAdminController {

    static final int MANAGE_PAGE = 50;
    static final int MANAGE_MAX = 200;

    private final MsAnnouncementService service;

    public MsAnnouncementAdminController(MsAnnouncementService service) {
        this.service = service;
    }

    @Operation(
            summary = "List announcements to manage",
            description = "Every announcement, for its managers, a keyset page at a time.")
    @GetMapping("/manage")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<KeysetPage<ManagedAnnouncementView>> manage(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        // Every announcement ever written, last changed first: a page at a time (plan 10/10, item 3.5).
        return ResponseEntity.ok(service.listPage(TimePage.of(limit, cursor, MANAGE_PAGE, MANAGE_MAX)));
    }

    @Operation(summary = "Create an announcement", description = "Adds an announcement.")
    @PostMapping
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ManagedAnnouncementView> create(@Valid @RequestBody AnnouncementDraftRequest request) {
        ManagedAnnouncementView created = service.create(request, currentUserId());
        return Created.at("/api/v1/announcements/{id}", created.id(), created);
    }

    @Operation(summary = "Update an announcement", description = "Replaces an announcement.")
    @PutMapping("/{id}")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "update")
    public ResponseEntity<ManagedAnnouncementView> update(
            @PathVariable("id") Long id, @Valid @RequestBody AnnouncementDraftRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @Operation(summary = "Publish an announcement", description = "Makes an announcement visible to its audience.")
    @PostMapping("/{id}/publish")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "publish")
    public ResponseEntity<ManagedAnnouncementView> publish(
            @PathVariable("id") Long id, @Valid @RequestBody AnnouncementVersionRequest request) {
        return ResponseEntity.ok(service.publish(id, request.lockVersion()));
    }

    @Operation(summary = "Archive an announcement", description = "Archives an announcement.")
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
