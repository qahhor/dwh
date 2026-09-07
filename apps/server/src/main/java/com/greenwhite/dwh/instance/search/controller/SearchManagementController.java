package com.greenwhite.dwh.instance.search.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.*;
import com.greenwhite.dwh.instance.search.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/search", produces="application/json")
public class SearchManagementController {
    private final SearchSettingsService settings;
    private final SearchStatusService status;
    private final SearchService search;
    private final SearchAccessPolicy access;
    private final SearchJobService jobs;
    public SearchManagementController(SearchSettingsService settings, SearchStatusService status,
                                      SearchService search, SearchAccessPolicy access, SearchJobService jobs) {
        this.settings=settings; this.status=status; this.search=search; this.access=access; this.jobs=jobs;
    }
    @GetMapping("/settings")
    @RequiresPermission(form="platform.search", action="view")
    public SettingsSnapshot settings() { return settings.current(); }

    @PutMapping(value="/settings", consumes="application/json")
    @RequiresPermission(form="platform.settings", action="update")
    public SettingsSnapshot save(@RequestBody String json) {
        access.requireSettingsUpdate();
        return settings.save(SearchManagementDtos.decodeSave(json));
    }

    @PostMapping(value="/preview", consumes="application/json")
    @RequiresPermission(form="platform.search", action="view")
    public PreviewResult preview(@RequestBody String json) {
        access.requireSearchAccess();
        return search.preview(SearchManagementDtos.decodePreview(json));
    }

    @GetMapping("/status")
    @RequiresPermission(form="platform.search", action="view")
    public SearchStatusService.Status status() { return status.current(); }

    @PostMapping(value="/jobs", consumes="application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @RequiresPermission(form="platform.settings", action="update")
    public JobReceipt start(@RequestBody String json) {
        access.requireSettingsUpdate();
        return jobs.start(SearchManagementDtos.decodeStartJob(json));
    }

    @GetMapping("/jobs")
    @RequiresPermission(form="platform.search",action="view")
    public JobPage history(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) { return jobs.history(limit,cursor); }

    @GetMapping("/jobs/{id}")
    @RequiresPermission(form="platform.search",action="view")
    public JobStatus job(@PathVariable java.util.UUID id) { return jobs.current(id); }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @RequiresPermission(form="platform.settings",action="update")
    public JobReceipt cancel(@PathVariable java.util.UUID id) { return jobs.cancel(id); }

    @PostMapping(value="/jobs/{id}/retry",consumes="application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @RequiresPermission(form="platform.settings",action="update")
    public JobReceipt retry(@PathVariable java.util.UUID id,@RequestBody String json) {
        access.requireSettingsUpdate();
        return jobs.retry(id,SearchManagementDtos.decodeRetry(json));
    }
}
