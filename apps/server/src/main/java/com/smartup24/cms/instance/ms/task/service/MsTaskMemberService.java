package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import com.smartup24.cms.instance.ms.task.api.TaskMemberView;
import com.smartup24.cms.instance.ms.task.event.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository.TaskMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The participants of a task: author, responsible, executors and observers, who has viewed it, and the notifications
 * a change of participants sends (FR-TASK-8).
 */
@Service
public class MsTaskMemberService {

    private final MsTaskMemberRepository memberRepository;
    private final MsTaskAccess access;
    private final ApplicationEventPublisher eventPublisher;

    public MsTaskMemberService(
            MsTaskMemberRepository memberRepository, MsTaskAccess access, ApplicationEventPublisher eventPublisher) {
        this.memberRepository = memberRepository;
        this.access = access;
        this.eventPublisher = eventPublisher;
    }

    /** The participants of a new task; the author is added as having seen it and is not notified. */
    @Transactional
    public void assignNewTask(
            TaskRecord task,
            Long reporterId,
            Long responsibleUserId,
            List<Long> executorUserIds,
            List<Long> observerUserIds) {
        memberRepository.addOrUpdateMember(task.id(), reporterId, MsTaskPref.INVOLVE_AUTHOR, true);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(task.id(), responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
        }
        addEach(task.id(), executorUserIds, MsTaskPref.INVOLVE_EXECUTOR);
        addEach(task.id(), observerUserIds, MsTaskPref.INVOLVE_OBSERVER);

        // FR-TASK-8: назначенные узнают о задаче с учётом роли; автор себя не уведомляет
        if (responsibleUserId != null) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    task.id(), task.title(), List.of(responsibleUserId), MsTaskPref.INVOLVE_RESPONSIBLE, reporterId));
        }
        publishAssigned(task.id(), task.title(), executorUserIds, MsTaskPref.INVOLVE_EXECUTOR, reporterId);
        publishAssigned(task.id(), task.title(), observerUserIds, MsTaskPref.INVOLVE_OBSERVER, reporterId);
    }

    /**
     * The participant lists a PATCH sent, replaced as sent; whoever joins or leaves a role is told. The caller has
     * checked the participants and passes the members as they were before the change.
     */
    @Transactional
    public void applyPatch(
            Long taskId, String taskTitle, MsTaskPatch requested, List<TaskMemberRecord> oldMembers, Long actorId) {
        boolean executors = requested.executorUserIdsPresent() && requested.executorUserIds() != null;
        boolean observers = requested.observerUserIdsPresent() && requested.observerUserIds() != null;
        if (requested.responsibleUserIdPresent()) {
            replaceResponsible(taskId, requested.responsibleUserId());
        }
        if (executors) {
            replaceMembers(taskId, MsTaskPref.INVOLVE_EXECUTOR, requested.executorUserIds());
        }
        if (observers) {
            replaceMembers(taskId, MsTaskPref.INVOLVE_OBSERVER, requested.observerUserIds());
        }

        if (requested.responsibleUserIdPresent()) {
            publishResponsibleChange(taskId, taskTitle, oldMembers, requested.responsibleUserId(), actorId);
        }
        if (executors) {
            publishListChange(
                    taskId, taskTitle, MsTaskPref.INVOLVE_EXECUTOR, oldMembers, requested.executorUserIds(), actorId);
        }
        if (observers) {
            publishListChange(
                    taskId, taskTitle, MsTaskPref.INVOLVE_OBSERVER, oldMembers, requested.observerUserIds(), actorId);
        }
    }

    @Transactional
    public void setResponsible(Long taskId, Long responsibleUserId) {
        access.find(taskId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(taskId, responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
            var task = access.find(taskId);
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, task.title(), List.of(responsibleUserId), MsTaskPref.INVOLVE_RESPONSIBLE, null));
        }
    }

    @Transactional
    public void setResponsible(Long taskId, Long responsibleUserId, Long currentUserId) {
        access.find(taskId, currentUserId);
        access.requireParticipant(currentUserId, responsibleUserId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(taskId, responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
            var task = access.find(taskId, currentUserId);
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, task.title(), List.of(responsibleUserId), MsTaskPref.INVOLVE_RESPONSIBLE, currentUserId));
        }
    }

    @Transactional
    public void setExecutors(Long taskId, List<Long> executorUserIds) {
        setExecutors(taskId, executorUserIds, null);
    }

    @Transactional
    public void setExecutors(Long taskId, List<Long> executorUserIds, Long currentUserId) {
        setMembers(taskId, MsTaskPref.INVOLVE_EXECUTOR, executorUserIds, currentUserId);
    }

    @Transactional
    public void setObservers(Long taskId, List<Long> observerUserIds) {
        setObservers(taskId, observerUserIds, null);
    }

    @Transactional
    public void setObservers(Long taskId, List<Long> observerUserIds, Long currentUserId) {
        setMembers(taskId, MsTaskPref.INVOLVE_OBSERVER, observerUserIds, currentUserId);
    }

    /** Все участники задачи — получатели уведомлений о ней (FR-TASK-4). */
    @Transactional(readOnly = true)
    public List<Long> memberUserIds(Long taskId) {
        return memberRepository.getTaskMembers(taskId).stream()
                .map(TaskMemberRecord::userId)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskMemberRecord> getTaskMembers(Long taskId) {
        return memberRepository.getTaskMembers(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskMemberView> getTaskMembers(Long taskId, Long currentUserId) {
        access.find(taskId, currentUserId);
        return MsTaskViews.all(memberRepository.getTaskMembers(taskId), MsTaskViews::member);
    }

    /** The user has seen the task; a user who is not a participant changes nothing. */
    @Transactional
    public void markViewed(Long taskId, Long userId) {
        access.find(taskId, userId);
        memberRepository.markViewed(taskId, userId);
    }

    /** With an actor: the task and the new participants are checked in the actor's scope; without: system call. */
    private void setMembers(Long taskId, String involveKind, List<Long> userIds, Long currentUserId) {
        if (currentUserId != null) {
            access.find(taskId, currentUserId);
            access.requireParticipants(currentUserId, userIds);
        } else {
            access.find(taskId);
        }
        memberRepository.removeMembersByKind(taskId, involveKind);
        if (userIds != null) {
            addEach(taskId, userIds, involveKind);
            List<Long> assigned =
                    userIds.stream().filter(Objects::nonNull).distinct().toList();
            if (!assigned.isEmpty()) {
                var task = currentUserId != null ? access.find(taskId, currentUserId) : access.find(taskId);
                eventPublisher.publishEvent(
                        new MsTaskEvents.TaskAssigned(taskId, task.title(), assigned, involveKind, currentUserId));
            }
        }
    }

    private void addEach(Long taskId, List<Long> userIds, String involveKind) {
        if (userIds == null) return;
        for (Long userId : userIds) {
            if (userId != null) {
                memberRepository.addOrUpdateMember(taskId, userId, involveKind, false);
            }
        }
    }

    private void publishAssigned(Long taskId, String title, List<Long> userIds, String involveKind, Long actorId) {
        if (userIds == null || userIds.isEmpty()) return;
        List<Long> assigned =
                userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!assigned.isEmpty()) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(taskId, title, assigned, involveKind, actorId));
        }
    }

    private void replaceResponsible(Long taskId, Long responsibleUserId) {
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(taskId, responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
        }
    }

    private void replaceMembers(Long taskId, String involveKind, List<Long> userIds) {
        memberRepository.removeMembersByKind(taskId, involveKind);
        userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(userId -> memberRepository.addOrUpdateMember(taskId, userId, involveKind, false));
    }

    private void publishResponsibleChange(
            Long taskId, String taskTitle, List<TaskMemberRecord> oldMembers, Long newResp, Long actorId) {
        Long oldResp = oldMembers.stream()
                .filter(m -> MsTaskPref.INVOLVE_RESPONSIBLE.equals(m.involveKind()))
                .map(TaskMemberRecord::userId)
                .findFirst()
                .orElse(null);
        if (newResp != null && !newResp.equals(oldResp)) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, taskTitle, List.of(newResp), MsTaskPref.INVOLVE_RESPONSIBLE, actorId));
        }
        if (oldResp != null && !oldResp.equals(newResp)) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskMemberRemoved(
                    taskId, taskTitle, List.of(oldResp), MsTaskPref.INVOLVE_RESPONSIBLE, actorId));
        }
    }

    private void publishListChange(
            Long taskId,
            String taskTitle,
            String involveKind,
            List<TaskMemberRecord> oldMembers,
            List<Long> requestedIds,
            Long actorId) {
        Set<Long> before = oldMembers.stream()
                .filter(m -> involveKind.equals(m.involveKind()))
                .map(TaskMemberRecord::userId)
                .collect(Collectors.toSet());
        Set<Long> after = requestedIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> added = after.stream().filter(uid -> !before.contains(uid)).toList();
        List<Long> removed = before.stream().filter(uid -> !after.contains(uid)).toList();
        if (!added.isEmpty()) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(taskId, taskTitle, added, involveKind, actorId));
        }
        if (!removed.isEmpty()) {
            eventPublisher.publishEvent(
                    new MsTaskEvents.TaskMemberRemoved(taskId, taskTitle, removed, involveKind, actorId));
        }
    }
}
