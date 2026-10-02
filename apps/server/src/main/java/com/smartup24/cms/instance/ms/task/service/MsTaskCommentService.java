package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.api.TaskCommentView;
import com.smartup24.cms.instance.ms.task.repository.MsTaskCommentRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MsTaskCommentService {

    private final MsTaskCommentRepository commentRepository;
    private final MsTaskAccess access;
    private final MsTaskMemberService memberService;
    private final MfFileService fileService;

    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    public MsTaskCommentService(
            MsTaskCommentRepository commentRepository,
            MsTaskAccess access,
            MsTaskMemberService memberService,
            MfFileService fileService,
            ApplicationEventPublisher eventPublisher,
            AuditLogService auditLogService) {
        this.commentRepository = commentRepository;
        this.access = access;
        this.memberService = memberService;
        this.fileService = fileService;
        this.eventPublisher = eventPublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public TaskCommentView addComment(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        String title = access.requireVisible(taskId, userId);
        if (fileIds != null) {
            for (UUID fileId : fileIds) {
                fileService.getFileMetadata(fileId, userId);
            }
        }
        var comment = commentRepository.create(taskId, userId, textMarkdown, fileIds);
        memberService.markViewed(taskId, userId);

        // FR-TASK-8: members learn about the comment; the author does not notify themselves
        var recipients = memberService.memberUserIds(taskId);
        eventPublisher.publishEvent(new MsTaskEvents.TaskCommented(taskId, title, recipients, userId));

        // The comment text is not written to the audit log: it is correspondence content,
        // and the audit log is read more widely than the task. The log keeps the fact and the author.
        auditLogService.logChange(
                "ms_task_comments",
                String.valueOf(comment.id()),
                "I",
                List.of("task_id", "created_by"),
                null,
                Map.of(
                        "task_id",
                        taskId,
                        "created_by",
                        userId,
                        "files_attached",
                        fileIds != null ? fileIds.size() : 0));

        return MsTaskViews.comment(comment);
    }

    /** The comments of a task the viewer may read, oldest first, a page at a time (plan 10/10, item 3.5). */
    @Transactional(readOnly = true)
    public KeysetPage<TaskCommentView> listComments(Long taskId, Long currentUserId, TimePage page) {
        access.requireVisible(taskId, currentUserId);
        return page.page(
                MsTaskViews.all(commentRepository.listComments(taskId, page), MsTaskViews::comment),
                view -> new TimePage.Position(view.createdAt(), view.id()));
    }
}
