package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.bulk.BulkItemScope;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bulk actions over selected tasks. Each task goes through the same single operation as from its card, in its own
 * transaction ({@link BulkItemScope}): its scope check, status check and audit apply; the answer reports every task.
 * Deliberately not transactional itself, so one failing task does not roll back the others.
 * <ul>
 *   <li>{@code status}, {@code params.statusId} — change the status;</li>
 *   <li>{@code priority}, {@code params.priority} — change the priority.</li>
 * </ul>
 */
@Service
public class MsTaskBulkService {

    private static final List<String> PRIORITIES = List.of(
            MsTaskPref.PRIORITY_LOW,
            MsTaskPref.PRIORITY_MEDIUM,
            MsTaskPref.PRIORITY_HIGH,
            MsTaskPref.PRIORITY_CRITICAL);

    private final MsTaskService taskService;
    private final MsTaskWorkflowService workflowService;
    private final MsTaskStatusService statusService;
    private final @Nullable BulkItemScope bulkItems;

    @Autowired
    public MsTaskBulkService(
            MsTaskService taskService,
            MsTaskWorkflowService workflowService,
            MsTaskStatusService statusService,
            @Nullable BulkItemScope bulkItems) {
        this.taskService = taskService;
        this.workflowService = workflowService;
        this.statusService = statusService;
        this.bulkItems = bulkItems;
    }

    public BulkResult run(BulkRequest body, Long currentUserId) {
        List<Long> ids = BulkRunner.checkedIds(body);
        String action = body.action() == null ? "" : body.action();
        return switch (action) {
            case "status" -> {
                long statusId = body.params() == null
                        ? 0
                        : body.params().path("statusId").asLong(0);
                if (statusService.listStatuses().stream()
                        .noneMatch(status -> Long.valueOf(statusId).equals(status.id()))) {
                    throw BulkRunner.invalidParam("statusId", "unknown status");
                }
                yield BulkRunner.run(
                        action, ids, id -> workflowService.changeStatus(id, statusId, currentUserId), bulkItems);
            }
            case "priority" -> {
                String priority = body.params() == null
                        ? ""
                        : body.params().path("priority").asString("");
                if (!PRIORITIES.contains(priority)) {
                    throw BulkRunner.invalidParam("priority", "one of " + PRIORITIES);
                }
                yield BulkRunner.run(
                        action, ids, id -> taskService.changePriority(id, priority, currentUserId), bulkItems);
            }
            default -> throw BulkRunner.unknownAction(action);
        };
    }
}
