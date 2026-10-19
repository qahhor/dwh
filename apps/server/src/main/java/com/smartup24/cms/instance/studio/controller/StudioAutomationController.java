package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.CheckRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Options;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.DryRun;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.DryRunRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Run;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.RunNow;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Check;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import com.smartup24.cms.instance.studio.service.StudioAutomationTrials;
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

@RestController
@RequestMapping("/api/v1/automations")
public class StudioAutomationController {
    private static final String ONE = "/api/v1/automations/{id}";
    private static final String FORM = "studio.automations";

    private final StudioAutomationService automations;
    private final StudioAutomationTrials trials;

    public StudioAutomationController(StudioAutomationService automations, StudioAutomationTrials trials) {
        this.automations = automations;
        this.trials = trials;
    }

    @Operation(
            summary = "Describe what an automation may use",
            description = "The channels this installation delivers on (in_app always; email and telegram when their"
                    + " provider is configured) and the limits: automations per form, actions, the depth of a chain"
                    + " and the runs of one automation for one record an hour.")
    @GetMapping("/options")
    @RequiresPermission(form = FORM, action = "view")
    @ResponseStatus(HttpStatus.OK)
    public Options automationOptions() {
        return automations.options();
    }

    @Operation(
            summary = "List automations",
            description = "Of one live form or of all, the newest first, each with its last run; limit 1 to 100"
                    + " (default 20, else 422).")
    @GetMapping
    @RequiresPermission(form = FORM, action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<Automation> automations(
            @RequestParam(required = false) @Nullable String form,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return automations.list(form, limit, cursor);
    }

    @Operation(summary = "Read an automation", description = "Its revision as ETag. 404 for an unknown automation.")
    @GetMapping("/{id}")
    @RequiresPermission(form = FORM, action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Automation> automation(@PathVariable long id) {
        Automation automation = automations.get(id);
        return ResponseEntity.ok().eTag(Revisions.etag(automation.revision())).body(automation);
    }

    @Operation(
            summary = "Create an automation",
            description = "A live form, a name, a trigger (created, updated, saved, transition, deleted or scheduled),"
                    + " an optional condition formula and 1 to 10 actions (set_field, create_record, transition,"
                    + " notify), checked against the active versions of the forms — 422 at the part to fix, or above"
                    + " 20 automations per form. The caller becomes its author, the person it runs as: 403 when the"
                    + " caller may not do by hand what an action does. Audited.")
    @PostMapping
    @RequiresPermission(form = FORM, action = "manage")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<Automation> createAutomation(@Valid @RequestBody AutomationRequest body) {
        Automation automation = automations.create(body);
        ResponseEntity<Automation> created = Created.at(ONE, automation.id(), automation);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .eTag(Revisions.etag(automation.revision()))
                .body(automation);
    }

    @Operation(
            summary = "Change, switch on or pause an automation",
            description = "If-Match with its revision (428 without it, 409 when stale). Its form never changes. The"
                    + " caller becomes its author, checked as on creation. Audited.")
    @PutMapping("/{id}")
    @RequiresPermission(form = FORM, action = "manage")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Automation> updateAutomation(
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody AutomationRequest body) {
        Automation automation = automations.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag(Revisions.etag(automation.revision())).body(automation);
    }

    @Operation(
            summary = "Delete an automation",
            description = "If-Match with its revision (428/409). Its run log goes with it. Audited.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = FORM, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAutomation(
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        automations.delete(id, ifMatch);
    }

    @Operation(
            summary = "List the runs of an automation",
            description = "The newest first: the trigger, the record, the outcome with its reason and what each action"
                    + " did; limit 1 to 100 (default 20).")
    @GetMapping("/{id}/runs")
    @RequiresPermission(form = FORM, action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<Run> automationRuns(
            @PathVariable long id,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return automations.runs(id, limit, cursor);
    }

    @Operation(
            summary = "Run a scheduled automation now",
            description = "Looks at the records of its form as its author and queues a run for each record that"
                    + " meets its condition (at most 1000 records read). 422 for an automation of an event, 409 while"
                    + " paused, 429 above 10 an hour. The runs follow in the run log.")
    @PostMapping("/{id}/runs")
    @RequiresPermission(form = FORM, action = "manage")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunNow runAutomationNow(@PathVariable long id) {
        return automations.runNow(id);
    }

    @Operation(
            summary = "Try an automation on a record",
            description = "Checks the automation as a save does, with the caller as its author, then runs it over the"
                    + " record in a transaction that never commits: whether the condition holds and what each action"
                    + " would write, create, run or send to whom. Nothing is stored or sent.")
    @PostMapping("/dry-run")
    @RequiresPermission(form = FORM, action = "manage")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public DryRun dryRunAutomation(@Valid @RequestBody DryRunRequest body) {
        return trials.dryRun(body);
    }

    @Operation(
            summary = "Check a formula of an automation",
            description = "Against the active version of the form: a condition gives a yes/no and may read old.<field>"
                    + " and changed.<field> for a change; a value gives the type of its target field. Answers the type"
                    + " or the first problem with its 1-based position; stores nothing.")
    @PostMapping("/formulas/check")
    @RequiresPermission(form = FORM, action = "manage")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public Check checkAutomationFormula(@Valid @RequestBody CheckRequest body) {
        return trials.check(body);
    }
}
