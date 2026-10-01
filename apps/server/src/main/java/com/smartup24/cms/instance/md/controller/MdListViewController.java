package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewRequest;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewResponse;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdListViewService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's own saved list views. Like personal settings, they are part of the profile: the permission
 * comes from the profile form every role has; the service checks access to the list itself in the registry.
 */
@RestController
@RequestMapping("/api/v1/list-views/{listCode}")
public class MdListViewController {

    private final MdListViewService service;

    public MdListViewController(MdListViewService service) {
        this.service = service;
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    @Operation(summary = "List saved views", description = "The saved views (filters, columns, sort) of a list.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<ViewResponse>> list(@PathVariable String listCode) {
        return ResponseEntity.ok(service.list(userId(), listCode));
    }

    @Operation(
            summary = "Save a view",
            description = "Saves the current filters, columns and sort of a list as a named view.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ViewResponse> create(@PathVariable String listCode, @RequestBody ViewRequest request) {
        ViewResponse view = service.create(userId(), listCode, request);
        return Created.at("/api/v1/list-views/{listCode}/{id}", new Object[] {listCode, view.id()}, view);
    }

    @Operation(summary = "Update a saved view", description = "Replaces a saved view of a list.")
    @PutMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<ViewResponse> update(
            @PathVariable String listCode, @PathVariable long id, @RequestBody ViewRequest request) {
        if (request.lockVersion() == null) {
            throw ApiException.validation(
                    "error.md.list_view_invalid",
                    List.of(FieldErrorItem.keyed(
                            "lockVersion", MdListViewService.LIST_VIEW_INVALID, "error.field.lock_version_required")));
        }
        return ResponseEntity.ok(service.update(userId(), listCode, id, request.lockVersion(), request));
    }

    @Operation(summary = "Delete a saved view", description = "Removes a saved view of a list.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable String listCode, @PathVariable long id) {
        service.delete(userId(), listCode, id);
        return ResponseEntity.noContent().build();
    }
}
