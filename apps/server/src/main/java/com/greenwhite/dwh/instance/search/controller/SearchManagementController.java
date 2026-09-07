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
    public SearchManagementController(SearchSettingsService settings, SearchStatusService status,
                                      SearchService search, SearchAccessPolicy access) {
        this.settings=settings; this.status=status; this.search=search; this.access=access;
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
}
