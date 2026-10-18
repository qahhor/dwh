package com.smartup24.cms.instance.report.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.MailingRequest;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Options;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Run;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.RunDetail;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.RunRequest;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

/**
 * {@code /api/v1/report-mailings} (ADR-0045): scheduled mailings of saved reports. A person who may read the report
 * keeps its mailings with {@code report.mailings.manage}, or with {@code report.mailings.own} when they own it; a
 * report they may not read answers 404. Each run executes the report as each recipient.
 */
@RestController
@RequestMapping("/api/v1/report-mailings")
public class ReportMailingController {
    private static final String ONE = "/api/v1/report-mailings/{id}";
    private static final String RUN = "/api/v1/report-mailings/{id}/runs/{runId}";

    private final ReportMailingService mailings;

    public ReportMailingController(ReportMailingService mailings) {
        this.mailings = mailings;
    }

    @Operation(
            summary = "Describe what a mailing may use",
            description = "The channels this installation delivers on (in_app always; email and telegram when their"
                    + " provider is configured), the most recipients, the largest attachment and whether the caller"
                    + " keeps the mailings of every report they may read.")
    @GetMapping("/options")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public Options reportMailingOptions() {
        return mailings.options();
    }

    @Operation(
            summary = "List the mailings of a saved report",
            description = "The newest first; limit 1 to 50 (default 20, else 422). 404 when the caller may not read"
                    + " the report, 403 without the right to keep its mailings.")
    @GetMapping
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<Mailing> reportMailings(
            @RequestParam long viewId,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return mailings.list(viewId, limit, cursor);
    }

    @Operation(summary = "Read a mailing", description = "Its revision as ETag. 404 for an unknown mailing.")
    @GetMapping("/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Mailing> reportMailing(@PathVariable long id) {
        Mailing mailing = mailings.get(id);
        return ResponseEntity.ok().eTag(Revisions.etag(mailing.revision())).body(mailing);
    }

    @Operation(
            summary = "Create a mailing",
            description = "A saved report the caller may read, a name, 1 to 50 active users, channels configured here,"
                    + " an XLSX attachment and/or a link, a daily, weekly or monthly schedule at a local time of a time"
                    + " zone, values of the report's parameters (presets counted when it runs) and a condition"
                    + " (always, has_rows, threshold on a grand total). 422 at the part to fix or above 10 mailings"
                    + " per report. Audited.")
    @PostMapping
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<Mailing> createReportMailing(@Valid @RequestBody MailingRequest body) {
        Mailing mailing = mailings.create(body);
        ResponseEntity<Mailing> created = Created.at(ONE, mailing.id(), mailing);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .eTag(Revisions.etag(mailing.revision()))
                .body(mailing);
    }

    @Operation(
            summary = "Change, pause or resume a mailing",
            description = "If-Match with its revision (428 without it, 409 when stale). The saved report never"
                    + " changes. Pausing stops scheduled sends; resuming sends from the next slot on. Audited.")
    @PutMapping("/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Mailing> updateReportMailing(
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody MailingRequest body) {
        Mailing mailing = mailings.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag(Revisions.etag(mailing.revision())).body(mailing);
    }

    @Operation(
            summary = "Delete a mailing",
            description = "If-Match with its revision (428/409). Its history goes with it. Audited.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReportMailing(
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        mailings.delete(id, ifMatch);
    }

    @Operation(
            summary = "Send a mailing now",
            description = "scope all: to every recipient of an active mailing (409 while paused); scope me: a test to"
                    + " the caller, paused or not, whatever the condition. At most 10 per mailing an hour (429). The"
                    + " run is queued; its outcome is in the run history.")
    @PostMapping("/{id}/runs")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Run> sendReportMailing(@PathVariable long id, @Valid @RequestBody RunRequest body) {
        Run run = mailings.request(id, body.scope());
        return Created.at(RUN, new Object[] {id, run.id()}, run);
    }

    @Operation(
            summary = "List the runs of a mailing",
            description = "The newest first: scheduled, sent now and test runs with their counts; limit 1 to 100"
                    + " (default 20).")
    @GetMapping("/{id}/runs")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<Run> reportMailingRuns(
            @PathVariable long id,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return mailings.runs(id, limit, cursor);
    }

    @Operation(
            summary = "Read a run of a mailing",
            description = "The run with what each recipient got on each channel: sent, queued (with the outbox state),"
                    + " skipped or failed, and why.")
    @GetMapping("/{id}/runs/{runId}")
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public RunDetail reportMailingRun(@PathVariable long id, @PathVariable long runId) {
        return mailings.run(id, runId);
    }
}
