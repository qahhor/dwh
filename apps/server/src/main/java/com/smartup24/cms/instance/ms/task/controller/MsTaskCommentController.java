package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.AddCommentRequest;
import com.smartup24.cms.instance.ms.task.api.TaskCommentView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskCommentService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/tasks/items/{taskId}/comments", "/api/v1/tasks/{taskId}/comments"})
public class MsTaskCommentController {

    private final MsTaskCommentService commentService;

    public MsTaskCommentController(MsTaskCommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "view")
    public ResponseEntity<List<TaskCommentView>> listComments(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(commentService.listComments(taskId, SecurityContext.getCurrentUserId()));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "create")
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
