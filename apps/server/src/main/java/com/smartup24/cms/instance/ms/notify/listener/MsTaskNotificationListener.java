package com.smartup24.cms.instance.ms.notify.listener;

import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns task tracker domain events into in-app notifications (FR-TASK-8), each written in its recipient's language
 * ({@link MdUserTexts}: the catalog strings {@code notify.task_*}).
 *
 * A plain listener (not AFTER_COMMIT): the notification must reach the database
 * in the SAME transaction as the task change itself; otherwise a rollback could leave a task
 * without a notification or the other way round. Delivery to SSE is already deferred
 * until commit inside MsNotificationService (MsSsePublisher).
 *
 * The author of the action is excluded from the recipients: we do not notify a person
 * about what they have just done themselves.
 */
@Component
@Profile("!migrate")
public class MsTaskNotificationListener {

    static final String ASSIGNED = "notify.task_assigned.";
    static final String STATUS = "notify.task_status.title";
    static final String COMMENT = "notify.task_comment.title";
    static final String DEADLINE_CHANGED = "notify.task_deadline_changed.title";
    static final String REMOVED = "notify.task_member_removed.";

    /** The reminder's title; {@code {id}} is the task's number. */
    static final String DEADLINE_TITLE = "notify.task_deadline.title";

    /** The reminder's text; {@code {title}} is the task's title, {@code {hours}} the window. */
    static final String DEADLINE_BODY = "notify.task_deadline.body";

    /** The role suffixes of the assignment and removal strings: responsible, co-executor, observer, any other. */
    static final List<String> ROLES = List.of("responsible", "executor", "observer", "member");

    private static final String LINK_TASK = "/tasks/items/";

    private final MsNotificationService notificationService;
    private final MdUserTexts texts;

    public MsTaskNotificationListener(MsNotificationService notificationService, MdUserTexts texts) {
        this.notificationService = notificationService;
        this.texts = texts;
    }

    @EventListener
    public void onTaskAssigned(MsTaskEvents.TaskAssigned event) {
        String roleKind = event.involveKind() != null ? event.involveKind() : "";
        notifyAll(
                event.recipientUserIds(),
                event.actorUserId(),
                "task_assigned",
                MsNotifyPref.TYPE_INFO,
                ASSIGNED + role(roleKind),
                Map.of(),
                event.taskTitle(),
                event.taskId(),
                "task-assigned-" + event.taskId() + (roleKind.isEmpty() ? "" : "-" + roleKind));
    }

    @EventListener
    public void onTaskStatusChanged(MsTaskEvents.TaskStatusChanged event) {
        notifyAll(
                event.recipientUserIds(),
                event.actorUserId(),
                "task_status",
                event.terminal() ? MsNotifyPref.TYPE_SUCCESS : MsNotifyPref.TYPE_INFO,
                STATUS,
                Map.of("status", String.valueOf(event.newStatusName())),
                event.taskTitle(),
                event.taskId(),
                // source_code includes the status: a change to a new status creates a new
                // notification, setting the same status again updates the previous one
                "task-status-" + event.taskId() + "-" + event.newStatusName());
    }

    @EventListener
    public void onTaskCommented(MsTaskEvents.TaskCommented event) {
        notifyAll(
                event.recipientUserIds(),
                event.actorUserId(),
                "task_comment",
                MsNotifyPref.TYPE_INFO,
                COMMENT,
                Map.of(),
                event.taskTitle(),
                event.taskId(),
                null); // comments are not collapsed: each one matters
    }

    @EventListener
    public void onTaskDeadlineChanged(MsTaskEvents.TaskDeadlineChanged event) {
        notifyAll(
                event.recipientUserIds(),
                event.actorUserId(),
                "task_deadline",
                MsNotifyPref.TYPE_WARNING,
                DEADLINE_CHANGED,
                Map.of(),
                event.taskTitle(),
                event.taskId(),
                "task-deadline-" + event.taskId());
    }

    @EventListener
    public void onTaskMemberRemoved(MsTaskEvents.TaskMemberRemoved event) {
        notifyAll(
                event.recipientUserIds(),
                event.actorUserId(),
                "task_member_removed",
                MsNotifyPref.TYPE_INFO,
                REMOVED + role(event.involveKind() != null ? event.involveKind() : ""),
                Map.of(),
                event.taskTitle(),
                event.taskId(),
                "task-removed-" + event.taskId());
    }

    /**
     * A deadline within the window: one reminder per task and person within it, however many nodes scan. The scan
     * publishes the event in a transaction of its own, and the send-once decision is made inside it.
     */
    @EventListener
    public void onTaskDeadlineApproaching(MsTaskEvents.TaskDeadlineApproaching event) {
        long userId = event.recipientUserId();
        if (!notificationService.isNotificationEnabled(userId, "task_deadline_reminder", "in_app")) {
            return;
        }
        Map<String, String> params = Map.of(
                "id", String.valueOf(event.taskId()),
                "title", String.valueOf(event.taskTitle()),
                "hours", String.valueOf(event.window().toHours()));
        notificationService.sendInAppNotificationOnce(
                userId,
                MsNotifyPref.TYPE_WARNING,
                texts.text(userId, DEADLINE_TITLE, params),
                texts.text(userId, DEADLINE_BODY, params),
                LINK_TASK + event.taskId(),
                "task_deadline_" + event.taskId(),
                event.window());
    }

    /** The role suffix of a member kind (R, E, O); any other kind is a plain member. */
    static String role(String involveKind) {
        return switch (involveKind) {
            case "R" -> ROLES.get(0);
            case "E" -> ROLES.get(1);
            case "O" -> ROLES.get(2);
            default -> ROLES.get(3);
        };
    }

    private void notifyAll(
            List<Long> recipients,
            Long actorUserId,
            String eventType,
            String type,
            String titleKey,
            Map<String, String> titleParams,
            String body,
            Long taskId,
            String sourceCode) {
        if (recipients == null) {
            return;
        }
        for (Long userId : recipients) {
            if (userId == null || userId.equals(actorUserId)) {
                continue;
            }
            if (!notificationService.isNotificationEnabled(userId, eventType, "in_app")) {
                continue;
            }
            notificationService.sendInAppNotification(
                    userId, type, texts.text(userId, titleKey, titleParams), body, LINK_TASK + taskId, sourceCode);
        }
    }
}
