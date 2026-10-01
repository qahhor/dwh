package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.task.api.AddProjectMemberRequest;
import com.smartup24.cms.instance.ms.task.api.CreateProjectRequest;
import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.ProjectView;
import com.smartup24.cms.instance.ms.task.api.UpdateProjectRequest;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsProjectListService;
import com.smartup24.cms.instance.ms.task.service.MsProjectService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks/projects")
public class MsProjectController {

    private final MsProjectService projectService;
    private final MsProjectListService projectListService;

    public MsProjectController(MsProjectService projectService, MsProjectListService projectListService) {
        this.projectService = projectService;
        this.projectListService = projectListService;
    }

    /** Deprecated for {@code GET /page} (ApiDeprecations); answers every project until its sunset. */
    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<List<ProjectView>> listProjects(
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(projectService.listProjects(state));
    }

    /**
     * The project list a page at a time on the registry (ms.projects, roadmap item 51): filter, sort, search and
     * the viewer's task counts. The project pickers search it a page at a time (plan 10/10, item 3.5).
     */
    @GetMapping("/page")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<KeysetPage<MsProjectListService.ProjectListItem>> pageProjects(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(
                projectListService.page(SecurityContext.getCurrentUserId(), limit, cursor, filter, sort, query, state));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<ProjectView> getProject(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ProjectView> createProject(@Valid @RequestBody CreateProjectRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        var project = projectService.createProject(
                body.name(), body.description(), body.state(), body.attributes(), currentUserId);
        return Created.at("/api/v1/tasks/projects/{id}", project.id(), project);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateProject(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateProjectRequest body) {
        long revision = projectService.updateProject(
                id, body.name(), body.description(), body.state(), body.attributes(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @PostMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> addMember(
            @PathVariable("id") Long id, @Valid @RequestBody AddProjectMemberRequest body) {
        projectService.addProjectMember(id, body.userId(), body.accessKind());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> removeMember(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        projectService.removeProjectMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    /** Deprecated for {@code GET /{id}/members/page} (ApiDeprecations); answers the whole list until its sunset. */
    @GetMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<List<ProjectMemberView>> getMembers(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectMembers(id));
    }

    /** The members of a project a page at a time, by name (plan 10/10, item 3.5). */
    @GetMapping("/{id}/members/page")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<KeysetPage<ProjectMemberView>> pageMembers(
            @PathVariable("id") Long id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(projectService.pageProjectMembers(id, limit, cursor));
    }
}
