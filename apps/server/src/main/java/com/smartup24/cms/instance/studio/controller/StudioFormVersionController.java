package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormSummary;
import com.smartup24.cms.instance.studio.service.StudioFormViews;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import com.smartup24.cms.instance.studio.service.StudioVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The live forms of the publisher: the list, one form with its versions, a new version, archive and restore (ADR-0041). */
@RestController
@RequestMapping("/api/v1/studio/forms")
@Profile("!migrate")
public class StudioFormVersionController {
    private static final String FORM = "/api/v1/studio/forms/{entityCode}";

    private final StudioFormViews views;
    private final StudioVersionService versions;

    public StudioFormVersionController(StudioFormViews views, StudioVersionService versions) {
        this.views = views;
        this.versions = versions;
    }

    @Operation(
            summary = "List the live forms",
            description = "Requires studio.forms.publish. The newest first; limit 1 to 100 (default 50, else 422) and"
                    + " the nextCursor of the previous page. Each form gives its status (active or archived), active"
                    + " version, versions, records, quarantine and why it fails to load, and the revision a change"
                    + " names in If-Match. An inactive module returns 404.")
    @GetMapping
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<KeysetPage<FormSummary>> forms(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(views.list(limit, cursor));
    }

    @Operation(
            summary = "Read a live form with its versions",
            description = "Requires studio.forms.publish. The declaration of the active version, every version and the"
                    + " revision as ETag. 404 for an unknown form or a value that is no live form code.")
    @GetMapping("/{entityCode}")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<FormDetail> form(@PathVariable String entityCode) {
        FormDetail detail = views.get(entityCode);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .eTag(Revisions.etag(detail.form().revision()))
                .body(detail);
    }

    @Operation(
            summary = "Activate a new version of a live form",
            description = "Requires studio.forms.publish, enabled activation and If-Match with the revision of the form"
                    + " (428 without it, 409 when stale). The body is a simple-form package of the same code (422"
                    + " otherwise). Only compatible changes are accepted: each refused change is named in a 422"
                    + " error.studio.change_incompatible with its field and change. 409 for an archived form, 422 for"
                    + " the same declaration as the active one or above 100 versions. The old versions never change;"
                    + " every node serves the new one at its next request. Audited.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = DeliveryPackage.class))))
    @PostMapping(value = "/{entityCode}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<FormDetail> newVersion(
            @PathVariable String entityCode,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest request)
            throws IOException {
        StudioPackages.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(StudioPackages.MAX_BYTES + 1);
        StudioPackages.requireSize(body.length);
        FormDetail detail = versions.newVersion(entityCode, ifMatch, body);
        ResponseEntity<FormDetail> created = Created.at(FORM, detail.form().entityCode(), detail);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .header("Cache-Control", "no-store")
                .eTag(Revisions.etag(detail.form().revision()))
                .body(detail);
    }

    @Operation(
            summary = "Archive a live form",
            description = "Requires studio.forms.publish and If-Match (428/409). The form leaves the menu; its records"
                    + " stay readable, with history and export, and every write answers 409 error.studio.archived. It"
                    + " no longer counts toward the limit of 100 active forms. 409 when already archived. Audited.")
    @PostMapping("/{entityCode}/archive")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<FormSummary> archive(
            @PathVariable String entityCode,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        FormSummary form = versions.archive(entityCode, ifMatch);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .eTag(Revisions.etag(form.revision()))
                .body(form);
    }

    @Operation(
            summary = "Restore an archived live form",
            description = "Requires studio.forms.publish and If-Match (428/409). The form returns to the menu and its"
                    + " records to writing; 422 error.studio.quota when 100 forms are already active, 409 when the form"
                    + " is not archived. A quarantined form stays out of service until it is released. Audited.")
    @PostMapping("/{entityCode}/restore")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<FormSummary> restore(
            @PathVariable String entityCode,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        FormSummary form = versions.restore(entityCode, ifMatch);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .eTag(Revisions.etag(form.revision()))
                .body(form);
    }
}
