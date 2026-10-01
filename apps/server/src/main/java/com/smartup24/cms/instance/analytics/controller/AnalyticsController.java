package com.smartup24.cms.instance.analytics.controller;

import com.smartup24.cms.instance.analytics.dto.AnalyticsSummaryDto;
import com.smartup24.cms.instance.analytics.dto.ProjectDistributionDto;
import com.smartup24.cms.instance.analytics.dto.TrendDataPointDto;
import com.smartup24.cms.instance.analytics.dto.UserWorkloadDto;
import com.smartup24.cms.instance.analytics.service.AnalyticsService;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @Operation(summary = "Get the task summary", description = "Task counts for the dashboard summary.")
    @GetMapping("/summary")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<AnalyticsSummaryDto> getSummary() {
        return ResponseEntity.ok(service.getSummary());
    }

    @Operation(summary = "Get task trends", description = "Task counts over time for the dashboard chart.")
    @GetMapping("/trends")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<TrendDataPointDto>> getTrends(
            @RequestParam(name = "range", defaultValue = "7d") String range) {
        return ResponseEntity.ok(service.getTrends(range));
    }

    @Operation(
            summary = "Get tasks by project",
            description = "How tasks are distributed between projects, for the dashboard.")
    @GetMapping("/projects")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<ProjectDistributionDto>> getProjects() {
        return ResponseEntity.ok(service.getProjectDistribution());
    }

    @Operation(summary = "Get the workload of users", description = "Task load per user, for the dashboard.")
    @GetMapping("/workload")
    @RequiresPermission(form = "analytics.dashboard", action = "view")
    public ResponseEntity<List<UserWorkloadDto>> getWorkload() {
        return ResponseEntity.ok(service.getUserWorkload());
    }
}
