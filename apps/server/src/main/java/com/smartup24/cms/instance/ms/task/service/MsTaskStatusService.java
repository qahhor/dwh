package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task statuses as the task services need them: the statuses in use and the one a new task starts in. The
 * statuses and types themselves are entities on the general runtime ({@link MsTaskStatusEntity},
 * {@link MsTaskTypeEntity}; ADR-0032, 8).
 */
@Service
public class MsTaskStatusService {

    private final MsTaskStatusRepository statusRepository;

    public MsTaskStatusService(MsTaskStatusRepository statusRepository) {
        this.statusRepository = statusRepository;
    }

    @Transactional(readOnly = true)
    public List<MsTaskStatusRepository.StatusRecord> listStatuses() {
        return statusRepository.listStatuses();
    }

    /** The status a new task starts in: the system status {@code new}, which is never archived or deleted. */
    @Transactional(readOnly = true)
    public MsTaskStatusRepository.StatusRecord defaultStatus() {
        return statusRepository
                .findByCode(MsTaskStatusEntity.INITIAL)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "error.task.default_status_missing"));
    }
}
