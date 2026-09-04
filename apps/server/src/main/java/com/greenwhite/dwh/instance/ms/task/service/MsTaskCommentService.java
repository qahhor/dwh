package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
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
    private final MfFileService fileService;

    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    public MsTaskCommentService(MsTaskCommentRepository commentRepository, MsTaskService taskService,
                                MfFileService fileService,
                                ApplicationEventPublisher eventPublisher,
                                AuditLogService auditLogService) {
        this.commentRepository = commentRepository;
        this.taskService = taskService;
        this.fileService = fileService;
        this.eventPublisher = eventPublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public MsTaskCommentRepository.CommentRecord addComment(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        var task = taskService.getTaskById(taskId, userId);
        if (fileIds != null) {
            for (UUID fileId : fileIds) {
                fileService.getFileMetadata(fileId, userId);
            }
        }
        var comment = commentRepository.create(taskId, userId, textMarkdown, fileIds);
        taskService.markViewed(taskId, userId);

        // FR-TASK-8: участники узнают о комментарии; автор себя не уведомляет
        var recipients = taskService.getTaskMembers(taskId).stream()
                .map(m -> m.userId()).distinct().toList();
        eventPublisher.publishEvent(new MsTaskEvents.TaskCommented(
                taskId, task.title(), recipients, userId));

        // Текст комментария в журнал не кладём: это содержимое переписки,
        // а аудит читают шире, чем задачу. В журнале — факт и автор.
        auditLogService.logChange("ms_task_comments", String.valueOf(comment.id()), "I",
                java.util.List.of("task_id", "created_by"),
                null,
                java.util.Map.of("task_id", taskId, "created_by", userId,
                        "files_attached", fileIds != null ? fileIds.size() : 0));

        return comment;
    }

    @Transactional(readOnly = true)
    public List<MsTaskCommentRepository.CommentRecord> listComments(Long taskId) {
        return commentRepository.listComments(taskId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskCommentRepository.CommentRecord> listComments(Long taskId, Long currentUserId) {
        taskService.getTaskById(taskId, currentUserId);
        return commentRepository.listComments(taskId);
    }
}
