package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The checks every task service starts with: the task is visible in the actor's data scope (ADR-0013), a referenced
 * project exists, a participant is someone the actor may see. One place, so the task services cannot drift apart.
 * Callers hold the transaction; this bean has none of its own.
 */
@Component
public class MsTaskAccess {

    private final MsTaskRepository taskRepository;
    private final MsProjectRepository projectRepository;
    private final MdScopeService scopeService;

    public MsTaskAccess(
            MsTaskRepository taskRepository, MsProjectRepository projectRepository, MdScopeService scopeService) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.scopeService = scopeService;
    }

    /** The task without a data scope: for internal callers that act for the system, not for a user. */
    public TaskRecord find(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND));
    }

    /** The task as the user may see it; an invisible task is as good as a missing one. */
    public TaskRecord find(Long taskId, Long currentUserId) {
        return taskRepository
                .findById(taskId, scopeService.filterForTasks(currentUserId))
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND));
    }

    /** A project the task refers to must exist; {@code null} means no project. */
    public void requireProject(Long projectId) {
        if (projectId != null) {
            ApiException.requirePresent(
                    projectRepository.findById(projectId), () -> new ApiException(ErrorCode.PROJECT_NOT_FOUND));
        }
    }

    public void requireParticipants(Long actorId, List<Long> userIds) {
        if (userIds == null) return;
        for (Long userId : userIds) {
            requireParticipant(actorId, userId);
        }
    }

    public void requireParticipant(Long actorId, Long userId) {
        if (userId != null && !scopeService.canAccessUser(actorId, userId)) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.assignee_unavailable");
        }
    }
}
