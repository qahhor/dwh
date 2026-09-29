package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.task.api.ChangeStatusRequest;
import com.smartup24.cms.instance.ms.task.api.CreateTaskRequest;
import com.smartup24.cms.instance.ms.task.api.ProjectTaskStatsView;
import com.smartup24.cms.instance.ms.task.api.TaskDetail;
import com.smartup24.cms.instance.ms.task.api.TaskListFilters;
import com.smartup24.cms.instance.ms.task.api.TaskView;
import com.smartup24.cms.instance.ms.task.api.UpdateTaskRequest;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskBulkService;
import com.smartup24.cms.instance.ms.task.service.MsTaskListService;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import com.smartup24.cms.instance.ms.task.service.MsTaskReadService;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import com.smartup24.cms.instance.ms.task.service.MsTaskWorkflowService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tasks: the list, the card and the commands on a task. Statuses and files have their own controllers. */
@RestController
@RequestMapping({"/api/v1/tasks/items", "/api/v1/tasks"})
public class MsTaskController {

    private final MsTaskService taskService;
    private final MsTaskReadService readService;
    private final MsTaskListService taskListService;
    private final MsTaskMemberService memberService;
    private final MsTaskWorkflowService workflowService;
    private final MsTaskBulkService bulkService;

    public MsTaskController(
            MsTaskService taskService,
            MsTaskReadService readService,
            MsTaskListService taskListService,
            MsTaskMemberService memberService,
            MsTaskWorkflowService workflowService,
            MsTaskBulkService bulkService) {
        this.taskService = taskService;
        this.readService = readService;
        this.taskListService = taskListService;
        this.memberService = memberService;
        this.workflowService = workflowService;
        this.bulkService = bulkService;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<KeysetPage<TaskView>> listTasks(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "statusId", required = false) Long statusId,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "hideTerminal", required = false) Boolean hideTerminal,
            @RequestParam(name = "assignedUserId", required = false) Long assignedUserId,
            @RequestParam(name = "memberRole", required = false) String memberRole,
            @RequestParam(name = "reporterId", required = false) Long reporterId,
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

    @GetMapping("/projects/stats")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<ProjectTaskStatsView>> getProjectStats() {
        return ResponseEntity.ok(readService.getProjectTaskStats(SecurityContext.getCurrentUserId()));
    }

    /** The card; reading it changes nothing (the client reports the view with {@code POST /{id}/view}). */
    @GetMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<TaskDetail> getTask(@PathVariable("id") Long id) {
        return ResponseEntity.ok(readService.getTaskDetail(id, SecurityContext.getCurrentUserId()));
    }

    /** The current user has seen the task: clears its "new" mark for this user. */
    @PostMapping("/{id}/view")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> markViewed(@PathVariable("id") Long id) {
        memberService.markViewed(id, SecurityContext.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/subtasks")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskView>> getSubtasks(@PathVariable("id") Long id) {
        return ResponseEntity.ok(readService.getSubtasks(id, SecurityContext.getCurrentUserId()));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TaskView> createTask(@Valid @RequestBody CreateTaskRequest body) {
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
                SecurityContext.getCurrentUserId());
        return Created.at("/api/v1/tasks/{id}", task.id(), task);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> updateTask(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateTaskRequest body) {
        // The revision the change was made from is required (plan item 3.6): If-Match, or expectedRevision.
        body.setExpectedRevision(Revisions.required(ifMatch, body.toPatch().expectedRevision()));
        taskService.updateTask(id, body, SecurityContext.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /** A bulk action over the selected tasks; the service checks the action and its parameters. */
    @PostMapping("/bulk")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<BulkResult> bulk(@RequestBody BulkRequest body) {
        return ResponseEntity.ok(bulkService.run(body, SecurityContext.getCurrentUserId()));
    }

    @PostMapping("/{id}/status")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> changeStatus(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeStatusRequest body) {
        workflowService.changeStatus(
                id,
                body.statusId(),
                Revisions.required(ifMatch, body.expectedRevision()),
                SecurityContext.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
