package com.greenwhite.dwh.instance.analytics.controller;

import com.greenwhite.dwh.instance.analytics.dto.AnalyticsSummaryDto;
import com.greenwhite.dwh.instance.analytics.dto.ProjectDistributionDto;
import com.greenwhite.dwh.instance.analytics.dto.TrendDataPointDto;
import com.greenwhite.dwh.instance.analytics.dto.UserWorkloadDto;
import com.greenwhite.dwh.instance.analytics.service.AnalyticsService;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<AnalyticsSummaryDto> getSummary() {
        return ResponseEntity.ok(service.getSummary());
    }

    @GetMapping("/trends")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<TrendDataPointDto>> getTrends(
            @RequestParam(name = "range", defaultValue = "7d") String range) {
        return ResponseEntity.ok(service.getTrends(range));
    }

    @GetMapping("/projects")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<ProjectDistributionDto>> getProjects() {
        return ResponseEntity.ok(service.getProjectDistribution());
    }

    @GetMapping("/workload")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<UserWorkloadDto>> getWorkload() {
        return ResponseEntity.ok(service.getUserWorkload());
    }
}
