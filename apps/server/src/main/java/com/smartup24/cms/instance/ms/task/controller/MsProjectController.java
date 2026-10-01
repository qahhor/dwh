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
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

    /**
     * The project list a page at a time on the registry (ms.projects, roadmap item 51): filter, sort, search and
     * the viewer's task counts. The project pickers search it a page at a time (plan 10/10, item 3.5).
     */
    @Operation(
            summary = "Page through projects",
            description =
                    "The projects a keyset page at a time on the registry: filter, sort, search and the caller's task counts.")
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

    @Operation(summary = "Get a project", description = "One project with its custom field values.")
    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<ProjectView> getProject(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
    }

    @Operation(
            summary = "Create a project",
            description = "Adds a project with its name, description, state and custom field values.")
    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ProjectView> createProject(@Valid @RequestBody CreateProjectRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        var project = projectService.createProject(
                body.name(), body.description(), body.state(), body.attributes(), currentUserId);
        return Created.at("/api/v1/tasks/projects/{id}", project.id(), project);
    }

    @Operation(summary = "Update a project", description = "Changes a project; names the revision it was read at.")
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

    @Operation(summary = "Add a project member", description = "Adds a user to a project.")
    @PostMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> addMember(
            @PathVariable("id") Long id, @Valid @RequestBody AddProjectMemberRequest body) {
        projectService.addProjectMember(id, body.userId(), body.accessKind());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a project member", description = "Removes a user from a project.")
    @DeleteMapping("/{id}/members/{userId}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> removeMember(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        projectService.removeProjectMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    /** The members of a project a page at a time, by name (plan 10/10, item 3.5). */
    @Operation(
            summary = "Page through project members",
            description = "The members of a project, by name, a keyset page at a time.")
    @GetMapping("/{id}/members/page")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<KeysetPage<ProjectMemberView>> pageMembers(
            @PathVariable("id") Long id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(projectService.pageProjectMembers(id, limit, cursor));
    }
}
