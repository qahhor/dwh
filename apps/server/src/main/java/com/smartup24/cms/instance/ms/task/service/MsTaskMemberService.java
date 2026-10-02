package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.api.TaskMemberView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository.TaskMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The participants of a task: author, responsible, executors and observers, who has viewed it, and the notifications
 * a change of them, of the deadline or of the status sends (FR-TASK-4, FR-TASK-8). The task record — with its
 * responsible person, executors and observers — is the general runtime's ({@link MsTaskEntity}); its hooks
 * ({@link MsTaskHooks}) call the methods that keep the participation rows and tell the people, in the transaction of
 * the save. The task module does not call notifications directly: it publishes {@link MsTaskEvents}.
 */
@Service
public class MsTaskMemberService {

    private final MsTaskMemberRepository memberRepository;
    private final MsTaskStatusRepository statusRepository;
    private final MsTaskAccess access;
    private final ApplicationEventPublisher eventPublisher;

    public MsTaskMemberService(
            MsTaskMemberRepository memberRepository,
            MsTaskStatusRepository statusRepository,
            MsTaskAccess access,
            ApplicationEventPublisher eventPublisher) {
        this.memberRepository = memberRepository;
        this.statusRepository = statusRepository;
        this.access = access;
        this.eventPublisher = eventPublisher;
    }

    /** The author of a new task takes part in it, having seen it; the author does not notify themselves. */
    @Transactional
    public void joinAuthor(long taskId, long authorId) {
        memberRepository.addOrUpdateMember(taskId, authorId, MsTaskPref.INVOLVE_AUTHOR, true);
    }

    /** The responsible person as a participant: the new one joins unread and is told, the old one leaves and is told. */
    @Transactional
    public void followResponsible(
            long taskId, String title, @Nullable Long before, @Nullable Long after, long actorId) {
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (after != null) {
            memberRepository.addOrUpdateMember(taskId, after, MsTaskPref.INVOLVE_RESPONSIBLE, false);
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, title, List.of(after), MsTaskPref.INVOLVE_RESPONSIBLE, actorId));
        }
        if (before != null && !before.equals(after)) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskMemberRemoved(
                    taskId, title, List.of(before), MsTaskPref.INVOLVE_RESPONSIBLE, actorId));
        }
    }

    /** Tells the people who joined or left a list of participants of one kind (E, O). */
    public void tellListChange(
            long taskId, String title, List<Long> before, List<Long> after, String involveKind, long actorId) {
        Set<Long> was = new LinkedHashSet<>(before);
        Set<Long> now = new LinkedHashSet<>(after);
        List<Long> added = now.stream().filter(id -> !was.contains(id)).toList();
        List<Long> removed = was.stream().filter(id -> !now.contains(id)).toList();
        if (!added.isEmpty()) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(taskId, title, added, involveKind, actorId));
        }
        if (!removed.isEmpty()) {
            eventPublisher.publishEvent(
                    new MsTaskEvents.TaskMemberRemoved(taskId, title, removed, involveKind, actorId));
        }
    }

    /** Tells every participant that the deadline moved. */
    @Transactional(readOnly = true)
    public void tellDeadline(
            long taskId, String title, @Nullable Instant before, @Nullable Instant after, long actorId) {
        eventPublisher.publishEvent(
                new MsTaskEvents.TaskDeadlineChanged(taskId, title, before, after, memberUserIds(taskId), actorId));
    }

    /** Tells every participant the task's new status, by its name, and whether it closes the task. */
    @Transactional(readOnly = true)
    public void tellStatus(long taskId, String title, String statusCode, long actorId) {
        var status = statusRepository.findAnyByCode(statusCode);
        eventPublisher.publishEvent(new MsTaskEvents.TaskStatusChanged(
                taskId,
                title,
                status.map(MsTaskStatusRepository.StatusRecord::name).orElse(statusCode),
                status.map(MsTaskStatusRepository.StatusRecord::isTerminal).orElse(false),
                memberUserIds(taskId),
                actorId));
    }

    /** All task members are recipients of its notifications (FR-TASK-4). */
    @Transactional(readOnly = true)
    public List<Long> memberUserIds(long taskId) {
        return memberRepository.getTaskMembers(taskId).stream()
                .map(TaskMemberRecord::userId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** The participants of a task the viewer may see, with their names and whether they have seen it. */
    @Transactional(readOnly = true)
    public List<TaskMemberView> getTaskMembers(long taskId, long viewerId) {
        access.requireVisible(taskId, viewerId);
        return memberRepository.getTaskMembers(taskId).stream()
                .map(member -> new TaskMemberView(
                        member.taskId(),
                        member.userId(),
                        member.userName(),
                        member.userLogin(),
                        member.userEmail(),
                        member.involveKind(),
                        member.isViewed()))
                .toList();
    }

    /** The user has seen the task; a user who is not a participant changes nothing. */
    @Transactional
    public void markViewed(long taskId, long userId) {
        access.requireVisible(taskId, userId);
        memberRepository.markViewed(taskId, userId);
    }
}
