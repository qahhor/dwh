package com.greenwhite.dwh.instance.upl.api;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.upl.UplPref;
import com.greenwhite.dwh.instance.upl.overview.UplOverviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The data overview page (roadmap wave 5): for those who see the uploads. */
@RestController
@RequestMapping("/api/v1/upl/overview")
public class UplOverviewController {

    private final UplOverviewService overview;

    public UplOverviewController(UplOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<UplOverviewService.Overview> get(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(overview.overview(days));
    }
}
