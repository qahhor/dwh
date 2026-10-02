package com.smartup24.cms.instance.ms.task;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskCommentRepository;
import com.smartup24.cms.instance.ms.task.service.MsTaskAccess;
import com.smartup24.cms.instance.ms.task.service.MsTaskCommentService;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/** The comments of a task check the task in the author's scope, then every attachment, before anything is written. */
class MsTaskCommentServiceTest {

    private final MsTaskCommentRepository commentRepository = mock(MsTaskCommentRepository.class);
    private final MsTaskAccess access = mock(MsTaskAccess.class);
    private final MsTaskMemberService memberService = mock(MsTaskMemberService.class);
    private final MfFileService fileService = mock(MfFileService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final MsTaskCommentService service = new MsTaskCommentService(
            commentRepository, access, memberService, fileService, eventPublisher, auditLogService);

    @Test
    void addCommentValidatesTaskAndEveryAttachmentBeforeWriting() {
        UUID fileId = UUID.fromString("6db360cf-26ba-4729-b8c9-f5adcf2df74c");
        MsTaskCommentRepository.CommentRecord comment = new MsTaskCommentRepository.CommentRecord(
                7L, 42L, 10L, "Комментарий", List.of(fileId), Instant.parse("2026-09-04T10:15:30Z"), "Автор", "author");
        when(access.requireVisible(42L, 10L)).thenReturn("Scoped task");
        when(commentRepository.create(42L, 10L, "Комментарий", List.of(fileId))).thenReturn(comment);
        when(memberService.memberUserIds(42L)).thenReturn(List.of());

        service.addComment(42L, 10L, "Комментарий", List.of(fileId));

        InOrder order = inOrder(access, fileService, commentRepository);
        order.verify(access).requireVisible(42L, 10L);
        order.verify(fileService).getFileMetadata(fileId, 10L);
        order.verify(commentRepository).create(42L, 10L, "Комментарий", List.of(fileId));
    }

    @Test
    void listCommentsValidatesTaskScopeBeforeReadingRows() {
        TimePage page = TimePage.of(null, null, 50, 200);
        service.listComments(42L, 10L, page);

        InOrder order = inOrder(access, commentRepository);
        order.verify(access).requireVisible(42L, 10L);
        order.verify(commentRepository).listComments(42L, page);
    }
}
