package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.api.TaskStatusView;
import com.smartup24.cms.instance.ms.task.api.TaskTypeView;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The status and type dictionaries as the API answers them. A bean of its own in front of
 * {@link MsTaskStatusService}: its cached reads and cache evictions only work when called through its proxy.
 */
@Service
public class MsTaskStatusViewService {

    private final MsTaskStatusService statusService;

    public MsTaskStatusViewService(MsTaskStatusService statusService) {
        this.statusService = statusService;
    }

    @Transactional
    public List<TaskStatusView> listStatuses() {
        return MsTaskViews.all(statusService.listStatuses(), MsTaskViews::status);
    }

    @Transactional
    public TaskStatusView createStatus(String pcode, String name, String color, int orderNo, boolean isTerminal) {
        return MsTaskViews.status(statusService.createStatus(pcode, name, color, orderNo, isTerminal));
    }

    @Transactional
    public long updateStatusRecord(
            Long id, String name, String color, Integer orderNo, Boolean isTerminal, long expectedRevision) {
        return statusService.updateStatusRecord(id, name, color, orderNo, isTerminal, expectedRevision);
    }

    @Transactional
    public void deleteStatus(Long id) {
        statusService.deleteStatus(id);
    }

    @Transactional
    public void reorderStatuses(List<Long> orderedIds) {
        statusService.reorderStatuses(orderedIds);
    }

    @Transactional
    public List<TaskTypeView> listTypes() {
        return MsTaskViews.all(statusService.listTypes(), MsTaskViews::type);
    }

    @Transactional
    public TaskTypeView createType(String code, String name, String icon, String color, int orderNo) {
        return MsTaskViews.type(statusService.createType(code, name, icon, color, orderNo));
    }

    @Transactional
    public long updateType(Long id, String name, String icon, String color, Integer orderNo, long expectedRevision) {
        return statusService.updateType(id, name, icon, color, orderNo, expectedRevision);
    }

    @Transactional
    public void deleteType(Long id) {
        statusService.deleteType(id);
    }

    @Transactional
    public void reorderTypes(List<Long> orderedIds) {
        statusService.reorderTypes(orderedIds);
    }
}
