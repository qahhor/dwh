package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingResponse;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRole;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportPreferencesInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A bounded shared catalog and owner-controlled ACL, without granting access to business data (ADR-0036). */
@RestController
@RequestMapping("/api/v1")
public class MdReportViewController {
    private final MdReportCatalogService catalog;
    private final MdPreferenceSharing sharing;
    private final MdReportPreferencesInput input;

    public MdReportViewController(
            MdReportCatalogService catalog, MdPreferenceSharing sharing, MdReportPreferencesInput input) {
        this.catalog = catalog;
        this.sharing = sharing;
        this.input = input;
    }

    @Operation(
            summary = "List accessible report definitions",
            description =
                    "Returns owned and role-shared reports with current visible fields: the reports catalog."
                            + " Narrowed by list, a part of the name (any case), ownership (mine or shared) and kind."
                            + " The default limit is 20, maximum 100. An incomplete or empty page can still have a next cursor when inaccessible definitions are omitted.")
    @GetMapping("/report-views")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<ReportViewItem> list(
            @RequestParam(required = false) @Nullable String listCode,
            @Parameter(description = "A part of the report's name, 1 to 80 characters")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(schema = @Schema(allowableValues = {"mine", "shared"})) @RequestParam(required = false) @Nullable
                    String owner,
            @Parameter(schema = @Schema(allowableValues = {"report", "widget"}))
                    @RequestParam(required = false)
                    @Nullable
                    String kind,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return catalog.list(userId(), input.catalog(listCode, q, owner, kind), limit, cursor);
    }

    @Operation(
            summary = "Read an accessible report definition",
            description = "An owned or role-shared report or widget with its current visible state, revalidated for"
                    + " the reader as in the catalog; 404 when the reader may not open it (ADR-0036, ADR-0045).")
    @GetMapping("/report-views/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ReportViewItem reportView(@PathVariable long id) {
        return catalog.require(userId(), id);
    }

    @Operation(
            summary = "List active sharing roles",
            description = "Returns only role identifiers and names under settings-update permission. "
                    + "The default limit is 20, maximum 100; larger limits return 422.")
    @GetMapping("/report-sharing/roles")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<SharingRole> roles(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return sharing.roles(limit, cursor);
    }

    @Operation(
            summary = "Read a report sharing policy",
            description = "Only the owner with settings-update permission reads the assigned role identifiers. "
                    + "Table views cannot be shared. ETag identifies the current view revision.")
    @GetMapping("/list-views/{listCode}/{id}/sharing")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<SharingResponse> sharing(@PathVariable String listCode, @PathVariable long id) {
        var response = sharing.report(userId(), listCode, id);
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    @Operation(
            summary = "Replace a report sharing policy",
            description =
                    "The owner assigns up to 20 distinct active roles with If-Match. "
                            + "The view revision and policy change atomically; an empty role list revokes sharing. Missing revision returns 428 and stale revision 409.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = SharingRequest.class))))
    @PutMapping(value = "/list-views/{listCode}/{id}/sharing", consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<SharingResponse> share(
            @PathVariable String listCode,
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest request)
            throws IOException {
        long revision = Revisions.required(ifMatch);
        var response = sharing.shareReport(
                userId(), listCode, id, revision, input.sharing(request).roleIds());
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }
}
