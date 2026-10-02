package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.TaskMemberView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a task has besides its record, which the general runtime serves at {@code /api/v1/entities/ms.tasks}
 * (ADR-0032, 8): its participants with their names and whether each has seen it, and the viewer's own mark of having
 * seen it — a mark of the viewer, not a change of the task, so no revision rises. Comments and files have their own
 * controllers under the same path.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class MsTaskController {

    private final MsTaskMemberService memberService;

    public MsTaskController(MsTaskMemberService memberService) {
        this.memberService = memberService;
    }

    /** The participants of one task: its author, responsible person, executors and observers. */
    @Operation(
            summary = "List task participants",
            description = "The participants of a task with their names, roles and whether each has seen it.")
    @GetMapping("/{id}/members")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskMemberView>> getMembers(@PathVariable("id") long id) {
        return ResponseEntity.ok(memberService.getTaskMembers(id, viewer()));
    }

    /** The current user has seen the task: clears its "new" mark for this user. */
    @Operation(
            summary = "Mark a task viewed",
            description = "Records that the caller has seen the task, clearing its new mark for them.")
    @PostMapping("/{id}/view")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> markViewed(@PathVariable("id") long id) {
        memberService.markViewed(id, viewer());
        return ResponseEntity.noContent().build();
    }

    private static long viewer() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId());
    }
}
