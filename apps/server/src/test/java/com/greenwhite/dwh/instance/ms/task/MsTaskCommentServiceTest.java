package com.greenwhite.dwh.instance.ms.task;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskCommentRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskCommentService;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MsTaskCommentServiceTest {

    private final MsTaskCommentRepository commentRepository = mock(MsTaskCommentRepository.class);
    private final MsTaskService taskService = mock(MsTaskService.class);
    private final MfFileService fileService = mock(MfFileService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final MsTaskCommentService service = new MsTaskCommentService(
            commentRepository, taskService, fileService, eventPublisher, auditLogService);

    @Test
    void addCommentValidatesTaskAndEveryAttachmentBeforeWriting() {
        UUID fileId = UUID.fromString("6db360cf-26ba-4729-b8c9-f5adcf2df74c");
        MsTaskRepository.TaskRecord task = task(42L);
        MsTaskCommentRepository.CommentRecord comment = new MsTaskCommentRepository.CommentRecord(
                7L, 42L, 10L, "Комментарий", List.of(fileId), Instant.parse("2026-09-04T10:15:30Z"));
        when(taskService.getTaskById(42L, 10L)).thenReturn(task);
        when(commentRepository.create(42L, 10L, "Комментарий", List.of(fileId))).thenReturn(comment);
        when(taskService.getTaskMembers(42L)).thenReturn(List.of());

        service.addComment(42L, 10L, "Комментарий", List.of(fileId));

        InOrder order = inOrder(taskService, fileService, commentRepository);
        order.verify(taskService).getTaskById(42L, 10L);
        order.verify(fileService).getFileMetadata(fileId, 10L);
        order.verify(commentRepository).create(42L, 10L, "Комментарий", List.of(fileId));
    }

    @Test
    void listCommentsValidatesTaskScopeBeforeReadingRows() {
        service.listComments(42L, 10L);

        InOrder order = inOrder(taskService, commentRepository);
        order.verify(taskService).getTaskById(42L, 10L);
        order.verify(commentRepository).listComments(42L);
    }

    private static MsTaskRepository.TaskRecord task(Long id) {
        Instant now = Instant.parse("2026-09-04T10:15:30Z");
        return new MsTaskRepository.TaskRecord(
                id, null, null, "Scoped task", "", 1L, "medium", 10L,
                Map.of(), null, null, null, now, now, 10L, 10L);
    }
}
