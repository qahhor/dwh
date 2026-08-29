package com.greenwhite.dwh.instance.ms.task.controller;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;

import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskCommentRepository;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskCommentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/tasks/items/{taskId}/comments", "/api/v1/tasks/{taskId}/comments"})
public class MsTaskCommentController {

    private final MsTaskCommentService commentService;

    public MsTaskCommentController(MsTaskCommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "view")
    public ResponseEntity<List<MsTaskCommentRepository.CommentRecord>> listComments(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(commentService.listComments(taskId));
    }

    @PostMapping
    @RequiresPermission(form = MsTaskPref.FORM_COMMENTS, action = "create")
    public ResponseEntity<MsTaskCommentRepository.CommentRecord> addComment(
            @PathVariable("taskId") Long taskId,
            @RequestBody AddCommentDto body) {

        String text = body != null ? body.resolveText() : null;
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Текст комментария не может быть пустым");
        }


        Long currentUserId = SecurityContext.getCurrentUserId();
        var comment = commentService.addComment(taskId, currentUserId, text, body.fileIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }

    public record AddCommentDto(
            String textMarkdown,
            String commentMarkdown,
            List<UUID> fileIds
    ) {
        public String resolveText() {
            if (textMarkdown != null && !textMarkdown.isBlank()) {
                return textMarkdown.trim();
            }
            if (commentMarkdown != null && !commentMarkdown.isBlank()) {
                return commentMarkdown.trim();
            }
            return "";
        }
    }
}
