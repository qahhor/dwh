package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewRequest;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewResponse;
import com.smartup24.cms.instance.md.api.MdPref;
import com.smartup24.cms.instance.md.service.MdListViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriTemplate;

/** The owner's saved table views, reports and widgets, changed using their observed revision (ADR-0036). */
@RestController
@RequestMapping("/api/v1/list-views/{listCode}")
public class MdListViewController {
    private final MdListViewService service;

    public MdListViewController(MdListViewService service) {
        this.service = service;
    }

    @Operation(
            summary = "List saved views",
            description =
                    "The owner's saved views of a list: table views by default, or requested reports and widgets. "
                            + "Stored report state is revalidated for the current viewer before it is returned.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<List<ViewResponse>> list(
            @PathVariable String listCode,
            @Parameter(description = "table, report or widget; repeat for several") @RequestParam(required = false)
                    List<String> kind) {
        return ResponseEntity.ok(service.list(userId(), listCode, kind));
    }

    @Operation(
            summary = "Save a view",
            description =
                    "Saves a named view after validating its state and current field access. Returns Location and ETag.")
    @PostMapping
    @NoIdempotency
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<ViewResponse> create(@PathVariable String listCode, @RequestBody ViewRequest request) {
        ViewResponse view = service.create(userId(), listCode, request);
        return ResponseEntity.created(new UriTemplate("/api/v1/list-views/{listCode}/{id}").expand(listCode, view.id()))
                .eTag(Revisions.etag(view.lockVersion()))
                .body(view);
    }

    @Operation(
            summary = "Update a saved view",
            description = "Only the owner replaces a saved view with its current If-Match revision. "
                    + "Missing revision returns 428 and stale revision 409; the answer carries the new ETag.")
    @PutMapping("/{id}")
    @NoIdempotency
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<ViewResponse> update(
            @PathVariable String listCode,
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody ViewRequest request) {
        ViewResponse view = service.update(userId(), listCode, id, Revisions.required(ifMatch), request);
        return ResponseEntity.ok().eTag(Revisions.etag(view.lockVersion())).body(view);
    }

    @Operation(
            summary = "Delete a saved view",
            description =
                    "Only the owner removes a saved view with its current If-Match revision. "
                            + "Missing revision returns 428 and stale revision 409. Dashboard references become unavailable placeholders.")
    @DeleteMapping("/{id}")
    @NoIdempotency
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(
            @PathVariable String listCode,
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        service.delete(userId(), listCode, id, Revisions.required(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }
}
