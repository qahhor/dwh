package com.greenwhite.dwh.instance.ms.task.controller;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsProjectRepository;
import com.greenwhite.dwh.instance.ms.task.service.MsProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks/projects")
public class MsProjectController {

    private final MsProjectService projectService;

    public MsProjectController(MsProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<List<MsProjectRepository.ProjectRecord>> listProjects(
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(projectService.listProjects(state));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<MsProjectRepository.ProjectRecord> getProject(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "create")
    public ResponseEntity<MsProjectRepository.ProjectRecord> createProject(@Valid @RequestBody CreateProjectDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        var project = projectService.createProject(body.name(), body.description(), body.state(), body.attributes(), currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    public ResponseEntity<Void> updateProject(@PathVariable("id") Long id, @RequestBody UpdateProjectDto body) {
        projectService.updateProject(id, body.name(), body.description(), body.state(), body.attributes());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    public ResponseEntity<Void> addMember(@PathVariable("id") Long id, @Valid @RequestBody AddMemberDto body) {
        projectService.addProjectMember(id, body.userId(), body.accessKind());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "update")
    public ResponseEntity<Void> removeMember(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        projectService.removeProjectMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_PROJECTS, action = "view")
    public ResponseEntity<List<MsProjectRepository.ProjectMemberRecord>> getMembers(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectMembers(id));
    }

    public record CreateProjectDto(
            @NotBlank String name,
            String description,
            String state,
            Map<String, Object> attributes
    ) {}

    public record UpdateProjectDto(
            String name,
            String description,
            String state,
            Map<String, Object> attributes
    ) {}

    public record AddMemberDto(
            Long userId,
            String accessKind
    ) {}
}
