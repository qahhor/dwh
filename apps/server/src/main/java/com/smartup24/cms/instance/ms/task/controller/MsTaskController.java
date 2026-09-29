package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.bulk.BulkItemScope;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.AttachFileRequest;
import com.smartup24.cms.instance.ms.task.api.ChangeStatusRequest;
import com.smartup24.cms.instance.ms.task.api.CreateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.CreateTaskRequest;
import com.smartup24.cms.instance.ms.task.api.CreateTypeRequest;
import com.smartup24.cms.instance.ms.task.api.ProjectTaskStatsView;
import com.smartup24.cms.instance.ms.task.api.TaskDetail;
import com.smartup24.cms.instance.ms.task.api.TaskFileView;
import com.smartup24.cms.instance.ms.task.api.TaskListFilters;
import com.smartup24.cms.instance.ms.task.api.TaskStatusView;
import com.smartup24.cms.instance.ms.task.api.TaskTypeView;
import com.smartup24.cms.instance.ms.task.api.TaskView;
import com.smartup24.cms.instance.ms.task.api.UpdateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.UpdateTaskRequest;
import com.smartup24.cms.instance.ms.task.api.UpdateTypeRequest;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskListService;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/tasks/items", "/api/v1/tasks"})
public class MsTaskController {

    private static final List<String> PRIORITIES = List.of(
            MsTaskPref.PRIORITY_LOW,
            MsTaskPref.PRIORITY_MEDIUM,
            MsTaskPref.PRIORITY_HIGH,
            MsTaskPref.PRIORITY_CRITICAL);

    private final MsTaskService taskService;
    private final MsTaskListService taskListService;

    private final @Nullable BulkItemScope bulkItems;

    public MsTaskController(MsTaskService taskService, MsTaskListService taskListService) {
        this(taskService, taskListService, null);
    }

    @Autowired
    public MsTaskController(
            MsTaskService taskService, MsTaskListService taskListService, @Nullable BulkItemScope bulkItems) {
        this.taskService = taskService;
        this.taskListService = taskListService;
        this.bulkItems = bulkItems;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<KeysetPage<TaskView>> listTasks(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "project_id", required = false) Long projectId,
            @RequestParam(name = "status_id", required = false) Long statusId,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "hide_terminal", required = false) Boolean hideTerminal,
            @RequestParam(name = "assigned_user_id", required = false) Long assignedUserId,
            @RequestParam(name = "member_role", required = false) String memberRole,
            @RequestParam(name = "reporter_id", required = false) Long reporterId,
            @RequestParam(name = "overdue", required = false) Boolean overdue) {

        // Registry list ms.tasks (ADR-0016); `search` and the flat filters are kept for existing callers.
        return ResponseEntity.ok(taskListService.viewPage(
                SecurityContext.getCurrentUserId(),
                limit,
                cursor,
                filter,
                sort,
                query != null && !query.isBlank() ? query : search,
                new TaskListFilters(
                        projectId, statusId, priority, hideTerminal, assignedUserId, memberRole, reporterId, overdue)));
    }

    // =========================================================================
    // Statuses API
    @GetMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskStatusView>> listStatuses() {
        return ResponseEntity.ok(taskService.listStatuses());
    }

    @PostMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<TaskStatusView> createStatus(@Valid @RequestBody CreateStatusRequest body) {
        var status =
                taskService.createStatus(body.pcode(), body.name(), body.color(), body.orderNo(), body.isTerminal());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    @PatchMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateStatus(@PathVariable("id") Long id, @RequestBody UpdateStatusRequest body) {
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
    public ResponseEntity<List<TaskTypeView>> listTypes() {
        return ResponseEntity.ok(taskService.listTypes());
    }

    @PostMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<TaskTypeView> createType(@Valid @RequestBody CreateTypeRequest body) {
        var type = taskService.createType(body.code(), body.name(), body.icon(), body.color(), body.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(type);
    }

    @PatchMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateType(@PathVariable("id") Long id, @RequestBody UpdateTypeRequest body) {
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
    public ResponseEntity<List<ProjectTaskStatsView>> getProjectStats() {
        return ResponseEntity.ok(taskService.getProjectTaskStats(SecurityContext.getCurrentUserId()));
    }

    // =========================================================================
    // Task Details & Subtasks
    // =========================================================================
    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<TaskDetail> getTask(@PathVariable("id") Long id) {
        return ResponseEntity.ok(taskService.getTaskDetail(id, SecurityContext.getCurrentUserId()));
    }

    @GetMapping("/{id}/files")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskFileView>> getTaskFiles(@PathVariable("id") Long id) {
        return ResponseEntity.ok(taskService.listTaskFiles(id, SecurityContext.getCurrentUserId()));
    }

    @PostMapping("/{id}/files")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> attachFile(@PathVariable("id") Long id, @RequestBody AttachFileRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        taskService.attachFile(id, body.fileId(), currentUserId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/files/{fileId}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> detachFile(@PathVariable("id") Long id, @PathVariable("fileId") UUID fileId) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        taskService.detachFile(id, fileId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/subtasks")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskView>> getSubtasks(@PathVariable("id") Long id) {
        return ResponseEntity.ok(taskService.getSubtasks(id, SecurityContext.getCurrentUserId()));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<TaskView> createTask(@Valid @RequestBody CreateTaskRequest body) {
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
                currentUserId);

        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateTask(@PathVariable("id") Long id, @RequestBody UpdateTaskRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();

        taskService.updateTask(id, body, currentUserId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Массовое действие над выбранными задачами. Каждая задача меняется той же одиночной операцией, что
     * и из карточки, в своей транзакции: скоуп, проверка статуса и аудит — её; ответ — итог по каждой задаче.
     * <ul>
     *   <li>{@code status}, {@code params.statusId} — сменить статус;</li>
     *   <li>{@code priority}, {@code params.priority} — сменить приоритет.</li>
     * </ul>
     */
    @PostMapping("/bulk")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<BulkResult> bulk(@RequestBody BulkRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        List<Long> ids = BulkRunner.checkedIds(body);
        String action = body.action() == null ? "" : body.action();
        return switch (action) {
            case "status" -> {
                long statusId = body.params() == null
                        ? 0
                        : body.params().path("statusId").asLong(0);
                if (taskService.listStatuses().stream()
                        .noneMatch(status -> Long.valueOf(statusId).equals(status.id()))) {
                    throw BulkRunner.invalidParam("statusId", "unknown status");
                }
                yield ResponseEntity.ok(BulkRunner.run(
                        action, ids, id -> taskService.changeStatus(id, statusId, currentUserId), bulkItems));
            }
            case "priority" -> {
                String priority = body.params() == null
                        ? ""
                        : body.params().path("priority").asString("");
                if (!PRIORITIES.contains(priority)) {
                    throw BulkRunner.invalidParam("priority", "one of " + PRIORITIES);
                }
                yield ResponseEntity.ok(BulkRunner.run(
                        action, ids, id -> taskService.changePriority(id, priority, currentUserId), bulkItems));
            }
            default -> throw BulkRunner.unknownAction(action);
        };
    }

    @PostMapping("/{id}/status")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> changeStatus(
            @PathVariable("id") Long id, @Valid @RequestBody ChangeStatusRequest body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        taskService.changeStatus(id, body.statusId(), body.expectedRevision(), currentUserId);
        return ResponseEntity.noContent().build();
    }
}
