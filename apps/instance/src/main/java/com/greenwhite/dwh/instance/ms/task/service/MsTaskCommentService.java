package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.instance.ms.task.repository.MsTaskCommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MsTaskCommentService {

    private final MsTaskCommentRepository commentRepository;
    private final MsTaskService taskService;

    public MsTaskCommentService(MsTaskCommentRepository commentRepository, MsTaskService taskService) {
        this.commentRepository = commentRepository;
        this.taskService = taskService;
    }

    @Transactional
    public MsTaskCommentRepository.CommentRecord addComment(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        taskService.getTaskById(taskId);
        var comment = commentRepository.create(taskId, userId, textMarkdown, fileIds);
        taskService.markViewed(taskId, userId);
        return comment;
    }

    @Transactional(readOnly = true)
    public List<MsTaskCommentRepository.CommentRecord> listComments(Long taskId) {
        return commentRepository.listComments(taskId);
    }
}
