package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Moving a task between statuses: a terminal status resolves it, the participants are told (FR-TASK-4). */
@Service
public class MsTaskWorkflowService {

    private final MsTaskAccess access;
    private final MsTaskRepository taskRepository;
    private final MsTaskStatusRepository statusRepository;
    private final MsTaskMemberService memberService;
    private final ApplicationEventPublisher eventPublisher;
    private final SearchChangePublisher searchChangePublisher;
    private final MsTaskAuditTrail auditTrail;

    public MsTaskWorkflowService(
            MsTaskAccess access,
            MsTaskRepository taskRepository,
            MsTaskStatusRepository statusRepository,
            MsTaskMemberService memberService,
            ApplicationEventPublisher eventPublisher,
            SearchChangePublisher searchChangePublisher,
            MsTaskAuditTrail auditTrail) {
        this.access = access;
        this.taskRepository = taskRepository;
        this.statusRepository = statusRepository;
        this.memberService = memberService;
        this.eventPublisher = eventPublisher;
        this.searchChangePublisher = searchChangePublisher;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public void changeStatus(Long taskId, Long newStatusId, Long currentUserId) {
        changeStatus(taskId, newStatusId, null, currentUserId);
    }

    /** @param expectedRevision the revision the client saw, or null to skip the optimistic check */
    @Transactional
    public void changeStatus(Long taskId, Long newStatusId, Long expectedRevision, Long currentUserId) {
        var existing = access.find(taskId, currentUserId);
        searchChangePublisher.lockStatusMembership(newStatusId);
        var newStatus = statusRepository
                .findById(newStatusId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.status_not_found"));

        Instant resolvedTime = newStatus.isTerminal() ? Instant.now() : null;
        taskRepository.updateStatus(taskId, newStatusId, resolvedTime, expectedRevision, currentUserId);

        var task = access.find(taskId, currentUserId);
        eventPublisher.publishEvent(new MsTaskEvents.TaskStatusChanged(
                taskId,
                task.title(),
                newStatus.name(),
                newStatus.isTerminal(),
                memberService.memberUserIds(taskId),
                currentUserId));

        searchChangePublisher.changed("TASK", taskId);
        auditTrail.statusChanged(taskId, existing.statusId(), newStatusId, newStatus.name());
    }
}
