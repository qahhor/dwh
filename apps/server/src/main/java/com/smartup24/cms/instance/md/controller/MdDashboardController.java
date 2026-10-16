package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.report.EntityDashboardReports;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardDetail;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardSummary;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingResponse;
import com.smartup24.cms.instance.md.service.MdDashboardService;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportPreferencesInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Named layouts are mutable user preferences; they never activate a form publication package (ADR-0036). */
@RestController
@RequestMapping("/api/v1/dashboards")
public class MdDashboardController {
    private final MdDashboardService dashboards;
    private final MdPreferenceSharing sharing;
    private final MdReportPreferencesInput input;
    private final EntityDashboardReports runner;

    public MdDashboardController(
            MdDashboardService dashboards,
            MdPreferenceSharing sharing,
            MdReportPreferencesInput input,
            EntityDashboardReports runner) {
        this.dashboards = dashboards;
        this.sharing = sharing;
        this.input = input;
        this.runner = runner;
    }

    @Operation(
            summary = "List accessible dashboards",
            description = "Returns owned and active-role-shared dashboard summaries. "
                    + "The default limit is 20 and maximum 100; all access is checked for the current reader.")
    @GetMapping
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<DashboardSummary> list(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return dashboards.list(userId(), limit, cursor);
    }

    @Operation(
            summary = "Create a named dashboard",
            description =
                    "Creates an owned dashboard containing up to 12 distinct accessible saved reports. "
                            + "Names are unique per owner; each owner may create at most 20 dashboards. Returns Location and ETag.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = DashboardRequest.class))))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.profile", action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<DashboardDetail> create(HttpServletRequest request) throws IOException {
        var response = dashboards.create(userId(), input.dashboard(request));
        return ResponseEntity.created(URI.create("/api/v1/dashboards/" + response.id()))
                .eTag(Revisions.etag(response.revision()))
                .body(response);
    }

    @Operation(
            summary = "Read a named dashboard",
            description =
                    "Reads the current persisted layout and revalidates every source for the reader. "
                            + "An unavailable widget retains only its identifier and width; no source name, fields or definition are disclosed.")
    @GetMapping("/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<DashboardDetail> get(@PathVariable long id) {
        var response = dashboards.get(userId(), id);
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    @Operation(
            summary = "Update a named dashboard",
            description =
                    "Only the owner may replace a layout with its current If-Match revision. "
                            + "A retained unavailable slot preserves its stored period binding without exposing it; missing revision returns 428 and stale revision 409.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = DashboardRequest.class))))
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.profile", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<DashboardDetail> update(
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest request)
            throws IOException {
        long revision = Revisions.required(ifMatch);
        var response = dashboards.update(userId(), id, revision, input.dashboard(request));
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    @Operation(
            summary = "Delete a named dashboard",
            description = "Only the owner may delete the layout with its current If-Match revision. "
                    + "Referenced reports remain intact. Missing revision returns 428 and stale revision 409.")
    @DeleteMapping("/{id}")
    @NoIdempotency
    @RequiresPermission(form = "md.profile", action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        dashboards.delete(userId(), id, Revisions.required(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Read dashboard sharing",
            description = "Only the owner with settings-update permission reads the explicit role policy. "
                    + "ETag identifies the dashboard revision, shared with layout changes.")
    @GetMapping("/{id}/sharing")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<SharingResponse> sharing(@PathVariable long id) {
        var response = sharing.dashboard(userId(), id);
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    @Operation(
            summary = "Replace dashboard sharing",
            description = "The owner with settings-update permission assigns up to 20 active roles. "
                    + "If-Match checks the layout revision; policy and incremented revision are committed atomically.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = SharingRequest.class))))
    @PutMapping(value = "/{id}/sharing", consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<SharingResponse> share(
            @PathVariable long id,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest request)
            throws IOException {
        long revision = Revisions.required(ifMatch);
        var response = sharing.shareDashboard(
                userId(), id, revision, input.sharing(request).roleIds());
        return ResponseEntity.ok().eTag(Revisions.etag(response.revision())).body(response);
    }

    @Operation(
            summary = "Run a dashboard widget",
            description =
                    "Requires the current layout revision in If-Match and rechecks report, field and data access. "
                            + "Both ISO dates or neither are accepted. DATE bounds are inclusive; timestamps use UTC start through the exclusive next day.")
    @GetMapping("/{id}/widgets/{viewId}/result")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public QueryAggregateResult result(
            @PathVariable long id,
            @PathVariable long viewId,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to) {
        return runner.result(id, Revisions.required(ifMatch), viewId, from, to).data();
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }
}
