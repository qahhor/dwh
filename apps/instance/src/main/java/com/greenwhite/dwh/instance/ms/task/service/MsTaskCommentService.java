package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.instance.ms.task.repository.MsTaskCommentRepository;
import com.greenwhite.dwh.instance.ms.task.event.MsTaskEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MsTaskCommentService {

    private final MsTaskCommentRepository commentRepository;
    private final MsTaskService taskService;

    private final ApplicationEventPublisher eventPublisher;

    public MsTaskCommentService(MsTaskCommentRepository commentRepository, MsTaskService taskService,
                                ApplicationEventPublisher eventPublisher) {
        this.commentRepository = commentRepository;
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public MsTaskCommentRepository.CommentRecord addComment(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        var task = taskService.getTaskById(taskId);
        var comment = commentRepository.create(taskId, userId, textMarkdown, fileIds);
        taskService.markViewed(taskId, userId);

        // FR-TASK-8: участники узнают о комментарии; автор себя не уведомляет
        var recipients = taskService.getTaskMembers(taskId).stream()
                .map(m -> m.userId()).distinct().toList();
        eventPublisher.publishEvent(new MsTaskEvents.TaskCommented(
                taskId, task.title(), recipients, userId));

        return comment;
    }

    @Transactional(readOnly = true)
    public List<MsTaskCommentRepository.CommentRecord> listComments(Long taskId) {
        return commentRepository.listComments(taskId);
    }
}
