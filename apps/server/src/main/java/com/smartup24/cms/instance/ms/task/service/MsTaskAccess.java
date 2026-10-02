package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import org.springframework.stereotype.Component;

/**
 * The check the task's own resources start with — its comments, files, participants and view mark: the task is
 * visible in the actor's data scope (ADR-0013); an invisible task answers as a missing one. The task itself is the
 * general runtime's ({@link MsTaskEntity}). Callers hold the transaction; this bean has none of its own.
 */
@Component
public class MsTaskAccess {

    private final MsTaskRepository taskRepository;
    private final MdScopeService scopeService;

    public MsTaskAccess(MsTaskRepository taskRepository, MdScopeService scopeService) {
        this.taskRepository = taskRepository;
        this.scopeService = scopeService;
    }

    /** The title of the task as the user may see it; an invisible task is as good as a missing one. */
    public String requireVisible(long taskId, long userId) {
        return taskRepository
                .visibleTitle(taskId, scopeService.filterForTasks(userId))
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND));
    }
}
