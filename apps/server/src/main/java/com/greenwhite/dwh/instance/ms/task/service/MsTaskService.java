package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.pagination.CursorUtils;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.ms.task.event.MsTaskEvents;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsProjectRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskMemberRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskStatusRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskTypeRepository;
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
    private final MsTaskTypeRepository typeRepository;
    private final MsTaskMemberRepository memberRepository;
    private final MsProjectRepository projectRepository;
    private final MdCustomFieldService customFieldService;
    private final MdScopeService scopeService;
    private final MfFileService fileService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer;
    private final com.greenwhite.dwh.instance.audit.service.AuditLogService auditLogService;



    public MsTaskService(
            MsTaskRepository taskRepository,
            MsTaskStatusRepository statusRepository,
            MsTaskTypeRepository typeRepository,
            MsTaskMemberRepository memberRepository,
            MsProjectRepository projectRepository,
            MdCustomFieldService customFieldService,
            MdScopeService scopeService,
            MfFileService fileService,
            ApplicationEventPublisher eventPublisher,
            com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer,
            com.greenwhite.dwh.instance.audit.service.AuditLogService auditLogService) {
        this.taskRepository = taskRepository;
        this.statusRepository = statusRepository;
        this.typeRepository = typeRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.customFieldService = customFieldService;
        this.scopeService = scopeService;
        this.fileService = fileService;
        this.eventPublisher = eventPublisher;
        this.typesenseIndexer = typesenseIndexer;
        this.auditLogService = auditLogService;
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
            getTaskById(parentTaskId, reporterId);
        }

        validateParticipant(reporterId, responsibleUserId);
        validateParticipants(reporterId, executorUserIds);
        validateParticipants(reporterId, observerUserIds);

        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        statusRepository.initDefaultStatusesIfEmpty();
        typeRepository.initDefaultTypesIfEmpty();

        var defaultStatus = statusRepository.findByPcode(MsTaskPref.STATUS_NEW)
                .orElseGet(() -> statusRepository.listStatuses().stream().findFirst()
                        .orElseThrow(() -> ApiException.badRequest(ErrorCode.INTERNAL_ERROR, "Базовый статус задачи не найден")));

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

        typesenseIndexer.indexTask(task.id());

        auditLogService.logChange("ms_tasks", String.valueOf(task.id()), "I",
                List.of("title", "project_id", "priority", "status_id"),
                null,
                Map.of("id", task.id(), "title", title, "projectId", projectId != null ? projectId : 0, "priority", safePriority));

        return task;
    }


    @Transactional
    public void attachFile(Long taskId, java.util.UUID fileId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        fileService.getFileMetadata(fileId, currentUserId);
        taskRepository.attachFile(taskId, fileId);
    }

    @Transactional
    public void detachFile(Long taskId, java.util.UUID fileId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        taskRepository.detachFile(taskId, fileId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskFileRecord> listTaskFiles(Long taskId) {
        return taskRepository.listTaskFiles(taskId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskFileRecord> listTaskFiles(Long taskId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        return taskRepository.listTaskFiles(taskId);
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
    public MsTaskRepository.TaskRecord getTaskById(Long taskId, Long currentUserId) {
        return taskRepository.findById(taskId, scopeService.filterForTasks(currentUserId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.TASK_NOT_FOUND, "Задача не найдена"));
    }

    @Transactional(readOnly = true)
    public KeysetPage<MsTaskRepository.TaskRecord> listTasks(
            int limit, String cursor, Long projectId, Long statusId, String priority, String search) {
        return listTasks(limit, cursor, projectId, statusId, priority, search, false);
    }

    @Transactional(readOnly = true)
    public KeysetPage<MsTaskRepository.TaskRecord> listTasks(
            int limit, String cursor, Long projectId, Long statusId, String priority, String search, Boolean hideTerminal) {
        return listTasks(limit, cursor, projectId, statusId, priority, search, hideTerminal, null);
    }

    @Transactional(readOnly = true)
    public KeysetPage<MsTaskRepository.TaskRecord> listTasks(
            int limit, String cursor, Long projectId, Long statusId, String priority, String search,
            Boolean hideTerminal, Long currentUserId) {

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
        List<MsTaskRepository.TaskRecord> tasks = taskRepository.listTasks(
                fetchLimit, afterId, projectId, statusId, priority, search, hideTerminal,
                scopeService.filterForTasks(currentUserId));

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

        getTaskById(taskId, currentUserId);

        if (projectId != null) {
            projectRepository.findById(projectId)
                    .orElseThrow(() -> ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Проект не найден"));
        }

        // Cycle check
        if (parentTaskId != null) {
            getTaskById(parentTaskId, currentUserId);
            if (parentTaskId.equals(taskId) || taskRepository.isDescendantOf(parentTaskId, taskId)) {
                throw ApiException.conflict(ErrorCode.TASK_PARENT_CYCLE, "Нельзя установить дочернюю задачу в качестве родительской (цикл)");
            }
        }

        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        var existing = getTaskById(taskId, currentUserId);
        String safePriority = normalizePriority(priority != null ? priority : existing.priority());

        taskRepository.update(taskId, new MsTaskRepository.TaskUpdateData(
                projectId, title, descriptionMarkdown, null, safePriority, parentTaskId, attributes, beginTime, endTime, null
        ), currentUserId);


        typesenseIndexer.indexTask(taskId);

        auditLogService.logChange("ms_tasks", String.valueOf(taskId), "U",
                List.of("title", "priority", "project_id"),
                Map.of("title", existing.title(), "priority", existing.priority()),
                Map.of("title", title != null ? title : existing.title(), "priority", safePriority));
    }

    @Transactional
    public void updateTask(Long taskId, String title, String descriptionMarkdown, String priority,
                           Long parentTaskId, Map<String, Object> attributes, Instant beginTime,
                           Instant endTime, Long currentUserId) {
        updateTask(taskId, null, title, descriptionMarkdown, priority, parentTaskId, attributes, beginTime, endTime, currentUserId);
    }

    @Transactional
    public void changeStatus(Long taskId, Long newStatusId, Long currentUserId) {
        var existing = getTaskById(taskId, currentUserId);
        var newStatus = statusRepository.findById(newStatusId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Статус не найден"));

        Instant resolvedTime = newStatus.isTerminal() ? Instant.now() : null;
        taskRepository.updateStatus(taskId, newStatusId, resolvedTime, currentUserId);

        var task = getTaskById(taskId, currentUserId);
        eventPublisher.publishEvent(new MsTaskEvents.TaskStatusChanged(
                taskId, task.title(), newStatus.name(), newStatus.isTerminal(),
                memberUserIds(taskId), currentUserId));

        typesenseIndexer.indexTask(taskId);

        auditLogService.logChange("ms_tasks", String.valueOf(taskId), "U",
                List.of("status_id"),
                Map.of("statusId", existing.statusId()),
                Map.of("statusId", newStatusId, "statusName", newStatus.name()));
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
    public void setResponsible(Long taskId, Long responsibleUserId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        validateParticipant(currentUserId, responsibleUserId);
        memberRepository.removeMembersByKind(taskId, MsTaskPref.INVOLVE_RESPONSIBLE);
        if (responsibleUserId != null) {
            memberRepository.addOrUpdateMember(taskId, responsibleUserId, MsTaskPref.INVOLVE_RESPONSIBLE, false);
            var task = getTaskById(taskId, currentUserId);
            eventPublisher.publishEvent(new MsTaskEvents.TaskAssigned(
                    taskId, task.title(), List.of(responsibleUserId), currentUserId));
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
    public void setExecutors(Long taskId, List<Long> executorUserIds, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        validateParticipants(currentUserId, executorUserIds);
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

    @Transactional
    public void setObservers(Long taskId, List<Long> observerUserIds, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        validateParticipants(currentUserId, observerUserIds);
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

    @Transactional(readOnly = true)
    public List<MsTaskMemberRepository.TaskMemberRecord> getTaskMembers(Long taskId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        return memberRepository.getTaskMembers(taskId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskRecord> getSubtasks(Long parentTaskId) {
        return taskRepository.findSubtasks(parentTaskId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskRecord> getSubtasks(Long parentTaskId, Long currentUserId) {
        getTaskById(parentTaskId, currentUserId);
        return taskRepository.findSubtasks(parentTaskId, scopeService.filterForTasks(currentUserId));
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskRecord> getAncestorChain(Long taskId) {
        return taskRepository.findAncestorChain(taskId);
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.TaskRecord> getAncestorChain(Long taskId, Long currentUserId) {
        getTaskById(taskId, currentUserId);
        return taskRepository.findAncestorChain(taskId, scopeService.filterForTasks(currentUserId));
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.ProjectTaskStats> getProjectTaskStats() {
        return taskRepository.getProjectTaskStats();
    }

    @Transactional(readOnly = true)
    public List<MsTaskRepository.ProjectTaskStats> getProjectTaskStats(Long currentUserId) {
        return taskRepository.getProjectTaskStats(scopeService.filterForTasks(currentUserId));
    }

    @Transactional
    public void markViewed(Long taskId, Long userId) {
        getTaskById(taskId, userId);
        memberRepository.markViewed(taskId, userId);
    }

    // =========================================================================
    // Dynamic Statuses
    // =========================================================================
    @Transactional
    public List<MsTaskStatusRepository.StatusRecord> listStatuses() {
        statusRepository.initDefaultStatusesIfEmpty();
        return statusRepository.listStatuses();
    }

    @Transactional
    public MsTaskStatusRepository.StatusRecord createStatus(String pcode, String name, String color, int orderNo, boolean isTerminal) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Название статуса обязательно");
        }
        return statusRepository.create(pcode, name, color, orderNo, isTerminal);
    }

    @Transactional
    public void updateStatusRecord(Long id, String name, String color, Integer orderNo, Boolean isTerminal) {
        statusRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Статус не найден"));
        statusRepository.update(id, name, color, orderNo, isTerminal);
    }

    @Transactional
    public void deleteStatus(Long id) {
        var status = statusRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Статус не найден"));
        if (status.pcode() != null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Нельзя удалить базовый системный статус");
        }
        boolean deleted = statusRepository.delete(id);
        if (!deleted) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Нельзя удалить статус, который используется в задачах");
        }
    }

    // =========================================================================
    // Dynamic Types
    // =========================================================================
    @Transactional
    public List<MsTaskTypeRepository.TypeRecord> listTypes() {
        typeRepository.initDefaultTypesIfEmpty();
        return typeRepository.listTypes();
    }

    @Transactional
    public MsTaskTypeRepository.TypeRecord createType(String code, String name, String icon, String color, int orderNo) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Код и название типа обязательны");
        }
        String cleanCode = code.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (typeRepository.findByCode(cleanCode).isPresent()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Тип с таким кодом уже существует");
        }
        return typeRepository.create(cleanCode, name, icon, color, orderNo);
    }

    @Transactional
    public void updateType(Long id, String name, String icon, String color, Integer orderNo) {
        typeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Тип задачи не найден"));
        typeRepository.update(id, name, icon, color, orderNo);
    }

    @Transactional
    public void deleteType(Long id) {
        var type = typeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Тип задачи не найден"));
        if (type.isSystem()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Нельзя удалить системный тип задачи");
        }
        typeRepository.delete(id);
    }

    @Transactional
    public void reorderStatuses(List<Long> orderedIds) {
        statusRepository.reorder(orderedIds);
    }

    @Transactional
    public void reorderTypes(List<Long> orderedIds) {
        typeRepository.reorder(orderedIds);
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

    private void validateParticipants(Long actorId, List<Long> userIds) {
        if (userIds == null) return;
        for (Long userId : userIds) {
            validateParticipant(actorId, userId);
        }
    }

    private void validateParticipant(Long actorId, Long userId) {
        if (userId != null && !scopeService.canAccessUser(actorId, userId)) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "Назначаемый пользователь недоступен");
        }
    }
}
