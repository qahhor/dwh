package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.pagination.CursorUtils;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.ms.task.event.MsTaskEvents;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsProjectRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskMemberRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskStatusRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MsTaskService {

    private final MsTaskRepository taskRepository;
    private final MsTaskStatusRepository statusRepository;
    private final MsTaskMemberRepository memberRepository;
    private final MsProjectRepository projectRepository;
    private final MdCustomFieldService customFieldService;
    private final ApplicationEventPublisher eventPublisher;

    public MsTaskService(
            MsTaskRepository taskRepository,
            MsTaskStatusRepository statusRepository,
            MsTaskMemberRepository memberRepository,
            MsProjectRepository projectRepository,
            MdCustomFieldService customFieldService,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.statusRepository = statusRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.customFieldService = customFieldService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public MsTaskRepository.TaskRecord createTask(
            Long projectId, Long parentTaskId, String title, String descriptionMarkdown,
            String priority, Long responsibleUserId, List<Long> executorUserIds,
            List<Long> observerUserIds, Map<String, Object> attributes,
            Instant beginTime, Instant endTime, Long reporterId) {

        if (projectId != null) {
            projectRepository.findById(projectId)
                    .orElseThrow(() -> ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Проект не найден"));
        }

        if (parentTaskId != null) {
            taskRepository.findById(parentTaskId)
                    .orElseThrow(() -> ApiException.notFound(ErrorCode.TASK_NOT_FOUND, "Родительская задача не найдена"));
        }

        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        statusRepository.initDefaultStatusesIfEmpty();
        var defaultStatus = statusRepository.findByPcode(MsTaskPref.STATUS_NEW)
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.INTERNAL_ERROR, "Базовый статус задачи не найден"));

        String safePriority = normalizePriority(priority);

        var task = taskRepository.create(new MsTaskRepository.TaskCreateData(
                projectId, parentTaskId, title, descriptionMarkdown,
                defaultStatus.id(), safePriority, reporterId, attributes, beginTime, endTime
        ), reporterId);

        // Assign Author
        memberRepository.addOrUpdateMember(task.id(), reporterId, MsTaskPref.INVOLVE_AUTHOR, true);

        // Assign Responsible (I-T1)
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(task.id(), responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
        }

        // Assign Executors
        if (executorUserIds != null) {
            for (Long execId : executorUserIds) {
                if (execId != null) {
                    memberRepository.addOrUpdateMember(task.id(), execId, MsTaskPref.INVOLVE_EXECUTOR, false);
                }
            }
        }

        // Assign Observers
        if (observerUserIds != null) {
            for (Long obsId : observerUserIds) {
                if (obsId != null) {
                    memberRepository.addOrUpdateMember(task.id(), obsId, MsTaskPref.INVOLVE_OBSERVER, false);
                }
            }
        }

        // FR-TASK-8: назначенные узнают о задаче; автор себя не уведомляет
        List<Long> assigned = new ArrayList<>();
        if (responsibleUserId != null) {
            assigned.add(responsibleUserId);
        }
        if (executorUserIds != null) {
            assigned.addAll(executorUserIds);
        }
        if (!assigned.isEmpty()) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    task.id(), task.title(), assigned, reporterId));
        }

        return task;
    }

    // Overload for backwards compatibility
    @Transactional
    public MsTaskRepository.TaskRecord createTask(
            Long projectId, Long parentTaskId, String title, String descriptionMarkdown,
            String priority, Long responsibleUserId, List<Long> executorUserIds,
            Map<String, Object> attributes, Instant beginTime, Instant endTime, Long reporterId) {
        return createTask(projectId, parentTaskId, title, descriptionMarkdown, priority,
                responsibleUserId, executorUserIds, null, attributes, beginTime, endTime, reporterId);
    }

    @Transactional(readOnly = true)
    public MsTaskRepository.TaskRecord getTaskById(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.TASK_NOT_FOUND, "Задача не найдена"));
    }

    @Transactional(readOnly = true)
    public KeysetPage<MsTaskRepository.TaskRecord> listTasks(
            int limit, String cursor, Long projectId, Long statusId, String priority, String search) {

        Long afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            String decoded = CursorUtils.decode(cursor);
            if (decoded != null) {
                try {
                    afterId = Long.parseLong(decoded);
                } catch (NumberFormatException ignored) {}
            }
        }

        int fetchLimit = limit + 1;
        List<MsTaskRepository.TaskRecord> tasks = taskRepository.listTasks(fetchLimit, afterId, projectId, statusId, priority, search);

        boolean hasMore = tasks.size() > limit;
        List<MsTaskRepository.TaskRecord> resultItems = hasMore ? tasks.subList(0, limit) : tasks;

        String nextCursor = null;
        if (hasMore && !resultItems.isEmpty()) {
            Long lastId = resultItems.get(resultItems.size() - 1).id();
            nextCursor = CursorUtils.encode(String.valueOf(lastId));
        }

        return KeysetPage.of(resultItems, nextCursor, hasMore, resultItems.size());
    }

    @Transactional
    public void updateTask(Long taskId, Long projectId, String title, String descriptionMarkdown, String priority,
                           Long parentTaskId, Map<String, Object> attributes, Instant beginTime,
                           Instant endTime, Long currentUserId) {

        getTaskById(taskId);

        if (projectId != null) {
            projectRepository.findById(projectId)
                    .orElseThrow(() -> ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Проект не найден"));
        }

        // Cycle check
        if (parentTaskId != null) {
            if (parentTaskId.equals(taskId) || taskRepository.isDescendantOf(parentTaskId, taskId)) {
                throw ApiException.conflict(ErrorCode.TASK_PARENT_CYCLE, "Нельзя установить дочернюю задачу в качестве родительской (цикл)");
            }
        }

        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        String safePriority = normalizePriority(priority);

        taskRepository.update(taskId, new MsTaskRepository.TaskUpdateData(
                projectId, title, descriptionMarkdown, null, safePriority, parentTaskId, attributes, beginTime, endTime, null
        ), currentUserId);
    }

    @Transactional
    public void updateTask(Long taskId, String title, String descriptionMarkdown, String priority,
                           Long parentTaskId, Map<String, Object> attributes, Instant beginTime,
                           Instant endTime, Long currentUserId) {
        updateTask(taskId, null, title, descriptionMarkdown, priority, parentTaskId, attributes, beginTime, endTime, currentUserId);
    }

    @Transactional
    public void changeStatus(Long taskId, Long newStatusId, Long currentUserId) {
        getTaskById(taskId);
        var newStatus = statusRepository.findById(newStatusId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Статус не найден"));

        Instant resolvedTime = newStatus.isTerminal() ? Instant.now() : null;
        taskRepository.updateStatus(taskId, newStatusId, resolvedTime, currentUserId);

        var task = getTaskById(taskId);
        eventPublisher.publishEvent(new MsTaskEvents.TaskStatusChanged(
                taskId, task.title(), newStatus.name(), newStatus.isTerminal(),
                memberUserIds(taskId), currentUserId));
    }

    @Transactional
    public void setResponsible(Long taskId, Long responsibleUserId) {
        getTaskById(taskId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(taskId, responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
            var task = getTaskById(taskId);
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, task.title(), List.of(responsibleUserId), null));
        }
    }

    @Transactional
    public void setExecutors(Long taskId, List<Long> executorUserIds) {
        getTaskById(taskId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_EXECUTOR);
        if (executorUserIds != null) {
            for (Long uid : executorUserIds) {
                if (uid != null) {
                    memberRepository.addOrUpdateMember(taskId, uid, MsTaskPref.INVOLVE_EXECUTOR, false);
                }
            }
        }
    }

    @Transactional
    public void setObservers(Long taskId, List<Long> observerUserIds) {
        getTaskById(taskId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_OBSERVER);
        if (observerUserIds != null) {
            for (Long uid : observerUserIds) {
                if (uid != null) {
                    memberRepository.addOrUpdateMember(taskId, uid, MsTaskPref.INVOLVE_OBSERVER, false);
                }
            }
        }
    }

    /** Все участники задачи — получатели уведомлений о ней (FR-TASK-4). */
    private List<Long> memberUserIds(Long taskId) {
        return memberRepository.getTaskMembers(taskId).stream()
                .map(MsTaskMemberRepository.TaskMemberRecord::userId)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MsTaskMemberRepository.TaskMemberRecord> getTaskMembers(Long taskId) {
        return memberRepository.getTaskMembers(taskId);
    }

    @Transactional
    public void markViewed(Long taskId, Long userId) {
        memberRepository.markViewed(taskId, userId);
    }

    @Transactional
    public List<MsTaskStatusRepository.StatusRecord> listStatuses() {
        statusRepository.initDefaultStatusesIfEmpty();
        return statusRepository.listStatuses();
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "medium";
        }
        String p = priority.trim().toLowerCase();
        return switch (p) {
            case "low" -> "low";
            case "high" -> "high";
            case "critical", "urgent" -> "critical";
            case "medium", "normal" -> "medium";
            default -> "medium";
        };
    }
}
