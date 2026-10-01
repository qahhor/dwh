package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.AddCommentRequest;
import com.smartup24.cms.instance.ms.task.api.TaskCommentView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskCommentService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/tasks/items/{taskId}/comments", "/api/v1/tasks/{taskId}/comments"})
public class MsTaskCommentController {

    static final int COMMENTS_PAGE = 50;
    static final int COMMENTS_MAX = 200;

    private final MsTaskCommentService commentService;

    public MsTaskCommentController(MsTaskCommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "List task comments", description = "The comments of a task.")
    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "view")
    public ResponseEntity<KeysetPage<TaskCommentView>> listComments(
            @PathVariable("taskId") Long taskId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        // A thread grows without bound: it is read oldest first, a page at a time (plan 10/10, item 3.5).
        return ResponseEntity.ok(commentService.listComments(
                taskId, SecurityContext.getCurrentUserId(), TimePage.of(limit, cursor, COMMENTS_PAGE, COMMENTS_MAX)));
    }

    @Operation(summary = "Comment on a task", description = "Adds a comment to a task.")
    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TaskCommentView> addComment(
            @PathVariable("taskId") Long taskId, @RequestBody AddCommentRequest body) {

        String text = body != null ? body.resolveText() : null;
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.comment_blank");
        }

        Long currentUserId = SecurityContext.getCurrentUserId();
        var comment = commentService.addComment(taskId, currentUserId, text, body.fileIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }
}
