package com.smartup24.cms.instance.search.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.service.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/search", produces = "application/json")
public class SearchManagementController {
    private final SearchSettingsService settings;
    private final SearchStatusService status;
    private final SearchService search;
    private final SearchAccessPolicy access;
    private final SearchJobService jobs;

    public SearchManagementController(
            SearchSettingsService settings,
            SearchStatusService status,
            SearchService search,
            SearchAccessPolicy access,
            SearchJobService jobs) {
        this.settings = settings;
        this.status = status;
        this.search = search;
        this.access = access;
        this.jobs = jobs;
    }

    @Operation(summary = "Get search settings", description = "The search settings in force, with their version.")
    @GetMapping("/settings")
    @RequiresPermission(form = "platform.search", action = "view")
    public SettingsSnapshot settings() {
        return settings.current();
    }

    @Operation(
            summary = "Save search settings",
            description = "Replaces the search settings; names the version it was read at.")
    @PutMapping(value = "/settings", consumes = "application/json")
    @RequiresPermission(form = "platform.settings", action = "update")
    public SettingsSnapshot save(@RequestBody String json) {
        access.requireSettingsUpdate();
        return settings.save(SearchManagementDtos.decodeSave(json));
    }

    @Operation(summary = "Preview a search", description = "Runs a query with draft settings, without saving them.")
    @PostMapping(value = "/preview", consumes = "application/json")
    @RequiresPermission(form = "platform.search", action = "view")
    public PreviewResult preview(@RequestBody String json) {
        access.requireSearchAccess();
        return search.preview(SearchManagementDtos.decodePreview(json));
    }

    @Operation(summary = "Get the search status", description = "The state of the search index and of its engine.")
    @GetMapping("/status")
    @RequiresPermission(form = "platform.search", action = "view")
    public SearchStatusService.Status status() {
        return status.current();
    }

    @Operation(
            summary = "Start a search job",
            description = "Queues a check, a rebuild or a rollback of the search index.")
    @PostMapping(value = "/jobs", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(form = "platform.settings", action = "update")
    public JobReceipt start(@RequestBody String json) {
        access.requireSettingsUpdate();
        return jobs.start(SearchManagementDtos.decodeStartJob(json));
    }

    @Operation(summary = "List search jobs", description = "The search index jobs, newest first, a page at a time.")
    @GetMapping("/jobs")
    @RequiresPermission(form = "platform.search", action = "view")
    public JobPage history(
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return jobs.history(limit, cursor);
    }

    @Operation(summary = "Get a search job", description = "The state and progress of one search index job.")
    @GetMapping("/jobs/{id}")
    @RequiresPermission(form = "platform.search", action = "view")
    public JobStatus job(@PathVariable UUID id) {
        return jobs.current(id);
    }

    @Operation(summary = "Cancel a search job", description = "Cancels a search index job that has not finished.")
    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(form = "platform.settings", action = "update")
    public JobReceipt cancel(@PathVariable UUID id) {
        return jobs.cancel(id);
    }

    @Operation(summary = "Retry a search job", description = "Queues a failed or cancelled search index job again.")
    @PostMapping(value = "/jobs/{id}/retry", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(form = "platform.settings", action = "update")
    public JobReceipt retry(@PathVariable UUID id, @RequestBody String json) {
        access.requireSettingsUpdate();
        return jobs.retry(id, SearchManagementDtos.decodeRetry(json));
    }
}
