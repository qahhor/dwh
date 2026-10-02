package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.ProjectProgressView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsProjectService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a project has besides its record, which the general runtime serves at {@code /api/v1/entities/ms.projects}
 * (ADR-0032, 8): the page of its members — a collection of its own until collections come (ADR-0032, 9.1) — and the
 * progress of projects over the tasks the viewer may see, which depends on the viewer's task scope and so is no field
 * of the record (ADR-0013).
 */
@RestController
@RequestMapping("/api/v1/tasks/projects")
public class MsProjectController {

    private final MsProjectService projectService;

    public MsProjectController(MsProjectService projectService) {
        this.projectService = projectService;
    }

    /** The members of a project a page at a time, by name (plan 10/10, item 3.5). */
    @Operation(
            summary = "Page through project members",
            description = "The members of a project, by name, a keyset page at a time.")
    @GetMapping("/{id}/members/page")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<KeysetPage<ProjectMemberView>> pageMembers(
            @PathVariable("id") long id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(projectService.pageProjectMembers(id, limit, cursor));
    }

    /** The progress of the projects of a list page; a project the viewer may not see is left out. */
    @Operation(
            summary = "Read the progress of projects",
            description = "Total and closed tasks of each named project over the tasks the caller may see, at most"
                    + " 200 projects.")
    @GetMapping("/progress")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<ProjectProgressView>> progress(@RequestParam(name = "ids") List<Long> ids) {
        long viewer = Objects.requireNonNull(SecurityContext.getCurrentUserId());
        return ResponseEntity.ok(projectService.progress(ids, viewer));
    }
}
