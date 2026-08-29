package com.greenwhite.dwh.instance.ms.task.controller;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskMemberRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskStatusRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskTypeRepository;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/tasks/items", "/api/v1/tasks"})
public class MsTaskController {

    private final MsTaskService taskService;

    public MsTaskController(MsTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<KeysetPage<MsTaskRepository.TaskRecord>> listTasks(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "project_id", required = false) Long projectId,
            @RequestParam(name = "status_id", required = false) Long statusId,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "search", required = false) String search) {

        return ResponseEntity.ok(taskService.listTasks(limit, cursor, projectId, statusId, priority, search));
    }

    // =========================================================================
    // Statuses API
    // =========================================================================
    @GetMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<MsTaskStatusRepository.StatusRecord>> listStatuses() {
        return ResponseEntity.ok(taskService.listStatuses());
    }

    @PostMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<MsTaskStatusRepository.StatusRecord> createStatus(@Valid @RequestBody CreateStatusDto body) {
        var status = taskService.createStatus(body.pcode(), body.name(), body.color(), body.orderNo(), body.isTerminal());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    @PatchMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateStatus(@PathVariable("id") Long id, @RequestBody UpdateStatusDto body) {
        taskService.updateStatusRecord(id, body.name(), body.color(), body.orderNo(), body.isTerminal());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> deleteStatus(@PathVariable("id") Long id) {
        taskService.deleteStatus(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/statuses/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> reorderStatuses(@RequestBody List<Long> orderedIds) {
        taskService.reorderStatuses(orderedIds);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Types API
    // =========================================================================
    @GetMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<MsTaskTypeRepository.TypeRecord>> listTypes() {
        return ResponseEntity.ok(taskService.listTypes());
    }

    @PostMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<MsTaskTypeRepository.TypeRecord> createType(@Valid @RequestBody CreateTypeDto body) {
        var type = taskService.createType(body.code(), body.name(), body.icon(), body.color(), body.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(type);
    }

    @PatchMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateType(@PathVariable("id") Long id, @RequestBody UpdateTypeDto body) {
        taskService.updateType(id, body.name(), body.icon(), body.color(), body.orderNo());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> deleteType(@PathVariable("id") Long id) {
        taskService.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/types/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> reorderTypes(@RequestBody List<Long> orderedIds) {
        taskService.reorderTypes(orderedIds);
        return ResponseEntity.noContent().build();
    }


    // =========================================================================
    // Project Stats API
    // =========================================================================
    @GetMapping("/projects/stats")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<MsTaskRepository.ProjectTaskStats>> getProjectStats() {
        return ResponseEntity.ok(taskService.getProjectTaskStats());
    }

    // =========================================================================
    // Task Details & Subtasks
    // =========================================================================
    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<TaskDetailResponse> getTask(@PathVariable("id") Long id) {
        var task = taskService.getTaskById(id);
        var members = taskService.getTaskMembers(id);
        var subtasks = taskService.getSubtasks(id);
        var ancestors = taskService.getAncestorChain(id);

        Long currentUserId = SecurityContext.getCurrentUserId();
        if (currentUserId != null) {
            taskService.markViewed(id, currentUserId);
        }

        return ResponseEntity.ok(new TaskDetailResponse(task, members, subtasks, ancestors));
    }

    @GetMapping("/{id}/subtasks")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<MsTaskRepository.TaskRecord>> getSubtasks(@PathVariable("id") Long id) {
        return ResponseEntity.ok(taskService.getSubtasks(id));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<MsTaskRepository.TaskRecord> createTask(@Valid @RequestBody CreateTaskDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();

        var task = taskService.createTask(
                body.projectId(),
                body.parentTaskId(),
                body.title(),
                body.descriptionMarkdown(),
                body.priority(),
                body.responsibleUserId(),
                body.executorUserIds(),
                body.observerUserIds(),
                body.attributes(),
                body.beginTime(),
                body.endTime(),
                currentUserId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateTask(@PathVariable("id") Long id, @RequestBody UpdateTaskDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();

        taskService.updateTask(
                id,
                body.projectId(),
                body.title(),
                body.descriptionMarkdown(),
                body.priority(),
                body.parentTaskId(),
                body.attributes(),
                body.beginTime(),
                body.endTime(),
                currentUserId
        );

        if (body.responsibleUserId() != null) {
            taskService.setResponsible(id, body.responsibleUserId());
        }

        if (body.executorUserIds() != null) {
            taskService.setExecutors(id, body.executorUserIds());
        }

        if (body.observerUserIds() != null) {
            taskService.setObservers(id, body.observerUserIds());
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/status")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> changeStatus(@PathVariable("id") Long id, @Valid @RequestBody ChangeStatusDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        taskService.changeStatus(id, body.statusId(), currentUserId);
        return ResponseEntity.noContent().build();
    }

    public record CreateTaskDto(
            @NotBlank String title,
            String descriptionMarkdown,
            Long projectId,
            Long parentTaskId,
            String priority,
            Long responsibleUserId,
            List<Long> executorUserIds,
            List<Long> observerUserIds,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime
    ) {}

    public record UpdateTaskDto(
            Long projectId,
            String title,
            String descriptionMarkdown,
            Long parentTaskId,
            String priority,
            Long responsibleUserId,
            List<Long> executorUserIds,
            List<Long> observerUserIds,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime
    ) {}

    public record ChangeStatusDto(
            Long statusId
    ) {}

    public record CreateStatusDto(
            String pcode,
            @NotBlank String name,
            String color,
            int orderNo,
            boolean isTerminal
    ) {}

    public record UpdateStatusDto(
            String name,
            String color,
            Integer orderNo,
            Boolean isTerminal
    ) {}

    public record CreateTypeDto(
            @NotBlank String code,
            @NotBlank String name,
            String icon,
            String color,
            int orderNo
    ) {}

    public record UpdateTypeDto(
            String name,
            String icon,
            String color,
            Integer orderNo
    ) {}

    public record TaskDetailResponse(
            MsTaskRepository.TaskRecord task,
            List<MsTaskMemberRepository.TaskMemberRecord> members,
            List<MsTaskRepository.TaskRecord> subtasks,
            List<MsTaskRepository.TaskRecord> ancestors
    ) {}
}
