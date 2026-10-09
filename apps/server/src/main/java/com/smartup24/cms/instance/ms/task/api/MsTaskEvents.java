package com.smartup24.cms.instance.ms.task.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Domain events of the task module (FR-TASK-8, ADR-0006 section 2.3 rule 3). The task module does not call
 * notifications directly: it announces what happened, and does not know who reacts or how.
 *
 * <p>Every event carries its recipients as a list: the task module decides whom to tell (it knows the members'
 * roles), not the subscriber.
 */
public final class MsTaskEvents {

    private MsTaskEvents() {}

    /** A user was assigned to the task in a role (R, E, O, etc.). */
    public record TaskAssigned(
            Long taskId, String taskTitle, List<Long> recipientUserIds, String involveKind, Long actorUserId) {
        public TaskAssigned(Long taskId, String taskTitle, List<Long> recipientUserIds, Long actorUserId) {
            this(taskId, taskTitle, recipientUserIds, null, actorUserId);
        }
    }

    /** The task's status changed. */
    public record TaskStatusChanged(
            Long taskId,
            String taskTitle,
            String newStatusName,
            boolean terminal,
            List<Long> recipientUserIds,
            Long actorUserId) {}

    /** A comment was added to the task. */
    public record TaskCommented(Long taskId, String taskTitle, List<Long> recipientUserIds, Long actorUserId) {}

    /** The task's deadline changed. */
    public record TaskDeadlineChanged(
            Long taskId,
            String taskTitle,
            Instant oldDeadline,
            Instant newDeadline,
            List<Long> recipientUserIds,
            Long actorUserId) {}

    /** A user was removed from the task. */
    public record TaskMemberRemoved(
            Long taskId, String taskTitle, List<Long> recipientUserIds, String involveKind, Long actorUserId) {}

    /**
     * The task's deadline comes within {@code window}: a reminder for one of its people. Published by the reminder
     * scan inside a transaction of its own per person, so the subscriber decides and records the reminder atomically.
     */
    public record TaskDeadlineApproaching(long taskId, String taskTitle, long recipientUserId, Duration window) {}
}
