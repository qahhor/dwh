package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository.TaskMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** The audit entries of {@code ms_tasks}: which columns a task operation changed, from what, to what. */
@Component
public class MsTaskAuditTrail {

    private static final String TABLE = "ms_tasks";

    private final AuditLogService auditLogService;

    public MsTaskAuditTrail(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    void created(TaskRecord task, String title, Long projectId, String priority) {
        auditLogService.logChange(
                TABLE,
                String.valueOf(task.id()),
                "I",
                List.of("title", "project_id", "priority", "status_id"),
                null,
                Map.of(
                        "id",
                        task.id(),
                        "title",
                        title,
                        "projectId",
                        projectId != null ? projectId : 0,
                        "priority",
                        priority));
    }

    void legacyUpdated(Long taskId, TaskRecord existing, String title, String priority) {
        auditLogService.logChange(
                TABLE,
                String.valueOf(taskId),
                "U",
                List.of("title", "priority", "project_id"),
                Map.of("title", existing.title(), "priority", existing.priority()),
                Map.of("title", title != null ? title : existing.title(), "priority", priority));
    }

    void statusChanged(Long taskId, Long oldStatusId, Long newStatusId, String newStatusName) {
        auditLogService.logChange(
                TABLE,
                String.valueOf(taskId),
                "U",
                List.of("status_id"),
                Map.of("statusId", oldStatusId),
                Map.of("statusId", newStatusId, "statusName", newStatusName));
    }

    /** Participants of one kind replaced outside a PATCH (the older assignment endpoints). */
    void membersReplaced(Long taskId, String involveKind, List<Long> userIds) {
        List<Long> assigned = userIds == null
                ? List.of()
                : userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        auditLogService.logChange(
                TABLE,
                String.valueOf(taskId),
                "U",
                List.of("members_" + involveKind),
                null,
                Map.of("involveKind", involveKind, "userIds", assigned));
    }

    /** A file attached to the task or taken off it. */
    void fileChanged(Long taskId, java.util.UUID fileId, boolean attached) {
        auditLogService.logChange(
                TABLE,
                String.valueOf(taskId),
                "U",
                List.of("files"),
                attached ? null : Map.of("fileId", fileId.toString()),
                attached ? Map.of("fileId", fileId.toString()) : null);
    }

    /** One entry for a PATCH: the row columns it wrote and the participant lists it replaced. */
    void patched(
            Long taskId,
            TaskRecord existing,
            List<TaskMemberRecord> oldMembers,
            MsTaskPatch requested,
            MsTaskPatch rowPatch) {
        var changes = new Changes();
        addRowChanges(changes, existing, rowPatch);
        addMemberChanges(changes, oldMembers, requested);
        if (!changes.columns.isEmpty()) {
            auditLogService.logChange(
                    TABLE, String.valueOf(taskId), "U", changes.columns, changes.oldRow, changes.newRow);
        }
    }

    private static void addRowChanges(Changes changes, TaskRecord existing, MsTaskPatch row) {
        if (row.titlePresent()) {
            changes.add("title", existing.title(), row.title());
        }
        if (row.descriptionMarkdownPresent()) {
            changes.add("description_markdown", existing.descriptionMarkdown(), row.descriptionMarkdown());
        }
        if (row.priorityPresent()) {
            changes.add("priority", existing.priority(), row.priority());
        }
        if (row.projectIdPresent()) {
            changes.add("project_id", existing.projectId(), row.projectId());
        }
        if (row.parentTaskIdPresent()) {
            changes.add("parent_task_id", existing.parentTaskId(), row.parentTaskId());
        }
        if (row.attributesPresent()) {
            changes.add("attributes", existing.attributes(), row.attributes());
        }
        if (row.beginTimePresent()) {
            changes.add("begin_time", auditTime(existing.beginTime()), auditTime(row.beginTime()));
        }
        if (row.endTimePresent()) {
            changes.add("end_time", auditTime(existing.endTime()), auditTime(row.endTime()));
        }
    }

    private static void addMemberChanges(Changes changes, List<TaskMemberRecord> oldMembers, MsTaskPatch requested) {
        if (requested.responsibleUserIdPresent()) {
            changes.add(
                    "responsible_user_id",
                    memberIds(oldMembers, MsTaskPref.INVOLVE_RESPONSIBLE).stream()
                            .findFirst()
                            .orElse(null),
                    requested.responsibleUserId());
        }
        if (requested.executorUserIdsPresent() && requested.executorUserIds() != null) {
            changes.add(
                    "executor_user_ids",
                    memberIds(oldMembers, MsTaskPref.INVOLVE_EXECUTOR),
                    normalizedIds(requested.executorUserIds()));
        }
        if (requested.observerUserIdsPresent() && requested.observerUserIds() != null) {
            changes.add(
                    "observer_user_ids",
                    memberIds(oldMembers, MsTaskPref.INVOLVE_OBSERVER),
                    normalizedIds(requested.observerUserIds()));
        }
    }

    private static List<Long> memberIds(List<TaskMemberRecord> members, String involveKind) {
        return members.stream()
                .filter(member -> involveKind.equals(member.involveKind()))
                .map(TaskMemberRecord::userId)
                .distinct()
                .toList();
    }

    private static List<Long> normalizedIds(List<Long> userIds) {
        return userIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String auditTime(Instant time) {
        return time != null ? time.toString() : null;
    }

    /** Changed columns with their old and new values, in the order they were found. */
    private static final class Changes {
        private final List<String> columns = new ArrayList<>();
        private final Map<String, Object> oldRow = new LinkedHashMap<>();
        private final Map<String, Object> newRow = new LinkedHashMap<>();

        void add(String column, Object oldValue, Object newValue) {
            columns.add(column);
            oldRow.put(column, oldValue);
            newRow.put(column, newValue);
        }
    }
}
