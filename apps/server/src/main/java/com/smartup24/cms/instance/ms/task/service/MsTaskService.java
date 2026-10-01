package com.smartup24.cms.instance.ms.task.service;

import static com.smartup24.cms.instance.ms.task.service.MsTaskPatchRules.normalizePriority;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.api.TaskView;
import com.smartup24.cms.instance.ms.task.api.UpdateTaskRequest;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTreeRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task itself: create it, change its fields, read it in the user's scope. Participants, files, statuses and the
 * card live in their own services ({@link MsTaskMemberService}, {@link MsTaskFileService},
 * {@link MsTaskWorkflowService}, {@link MsTaskReadService}).
 */
@Service
public class MsTaskService {

    private final MsTaskRepository taskRepository;
    private final MsTaskTreeRepository treeRepository;
    private final MdCustomFieldService customFieldService;
    private final MsTaskAccess access;
    private final MsTaskMemberService memberService;
    private final MsTaskStatusService statusService;
    private final ApplicationEventPublisher eventPublisher;
    private final SearchChangePublisher searchChangePublisher;
    private final MsTaskAuditTrail auditTrail;

    public MsTaskService(
            MsTaskRepository taskRepository,
            MsTaskTreeRepository treeRepository,
            MdCustomFieldService customFieldService,
            MsTaskAccess access,
            MsTaskMemberService memberService,
            MsTaskStatusService statusService,
            ApplicationEventPublisher eventPublisher,
            SearchChangePublisher searchChangePublisher,
            MsTaskAuditTrail auditTrail) {
        this.taskRepository = taskRepository;
        this.treeRepository = treeRepository;
        this.customFieldService = customFieldService;
        this.access = access;
        this.memberService = memberService;
        this.statusService = statusService;
        this.eventPublisher = eventPublisher;
        this.searchChangePublisher = searchChangePublisher;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public TaskView createTask(
            Long projectId,
            Long parentTaskId,
            String title,
            String descriptionMarkdown,
            String priority,
            Long responsibleUserId,
            List<Long> executorUserIds,
            List<Long> observerUserIds,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Long reporterId) {
        access.requireProject(projectId);
        if (parentTaskId != null) {
            access.find(parentTaskId, reporterId);
        }
        access.requireParticipant(reporterId, responsibleUserId);
        access.requireParticipants(reporterId, executorUserIds);
        access.requireParticipants(reporterId, observerUserIds);
        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        var defaultStatus = statusService.defaultStatus();
        String safePriority = normalizePriority(priority);

        searchChangePublisher.lockStatusMembership(defaultStatus.id());
        var task = taskRepository.create(
                new MsTaskRepository.TaskCreateData(
                        projectId,
                        parentTaskId,
                        title,
                        descriptionMarkdown,
                        defaultStatus.id(),
                        safePriority,
                        reporterId,
                        attributes,
                        beginTime,
                        endTime),
                reporterId);

        memberService.assignNewTask(task, reporterId, responsibleUserId, executorUserIds, observerUserIds);
        searchChangePublisher.changed("TASK", task.id());
        auditTrail.created(task, title, projectId, safePriority);
        return MsTaskViews.task(task);
    }

    // Overload for backwards compatibility
    @Transactional
    public TaskView createTask(
            Long projectId,
            Long parentTaskId,
            String title,
            String descriptionMarkdown,
            String priority,
            Long responsibleUserId,
            List<Long> executorUserIds,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Long reporterId) {
        return createTask(
                projectId,
                parentTaskId,
                title,
                descriptionMarkdown,
                priority,
                responsibleUserId,
                executorUserIds,
                null,
                attributes,
                beginTime,
                endTime,
                reporterId);
    }

    @Transactional(readOnly = true)
    public TaskRecord getTaskById(Long taskId) {
        return access.find(taskId);
    }

    @Transactional(readOnly = true)
    public TaskRecord getTaskById(Long taskId, Long currentUserId) {
        return access.find(taskId, currentUserId);
    }

    /** The PATCH body: only the properties the client sent change. */
    @Transactional
    public void updateTask(Long taskId, UpdateTaskRequest request, Long currentUserId) {
        updateTask(taskId, request.toPatch(), currentUserId);
    }

    /** The priority alone, as the bulk action sets it. */
    @Transactional
    public void changePriority(Long taskId, String priority, Long currentUserId) {
        updateTask(taskId, MsTaskPatch.builder().priority(priority).build(), currentUserId);
    }

    @Transactional
    public void updateTask(Long taskId, MsTaskPatch requested, Long currentUserId) {
        var existing = access.find(taskId, currentUserId);
        checkPatch(taskId, requested, currentUserId);

        MsTaskPatch rowPatch = MsTaskPatchRules.rowPatch(requested);
        MsTaskPatchRules.validateDeadline(existing, rowPatch);
        var oldMembers = memberService.getTaskMembers(taskId);

        taskRepository.patch(taskId, rowPatch, currentUserId);
        String taskTitle = rowPatch.titlePresent() ? rowPatch.title() : existing.title();
        memberService.applyPatch(taskId, taskTitle, requested, oldMembers, currentUserId);

        if (rowPatch.endTimePresent() && !Objects.equals(existing.endTime(), rowPatch.endTime())) {
            eventPublisher.publishEvent(new MsTaskEvents.TaskDeadlineChanged(
                    taskId,
                    taskTitle,
                    existing.endTime(),
                    rowPatch.endTime(),
                    memberService.memberUserIds(taskId),
                    currentUserId));
        }

        searchChangePublisher.changed("TASK", taskId);
        auditTrail.patched(taskId, existing, oldMembers, requested, rowPatch);
    }

    @Transactional
    public void updateTask(
            Long taskId,
            Long projectId,
            String title,
            String descriptionMarkdown,
            String priority,
            Long parentTaskId,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Long currentUserId) {
        access.find(taskId, currentUserId);
        access.requireProject(projectId);
        if (parentTaskId != null) {
            requireParent(taskId, parentTaskId, currentUserId);
        }
        if (attributes != null) {
            customFieldService.validateAttributes("TASK", attributes);
        }

        var existing = access.find(taskId, currentUserId);
        String safePriority = normalizePriority(priority != null ? priority : existing.priority());

        taskRepository.update(
                taskId,
                new MsTaskRepository.TaskUpdateData(
                        projectId,
                        title,
                        descriptionMarkdown,
                        null,
                        safePriority,
                        parentTaskId,
                        attributes,
                        beginTime,
                        endTime,
                        null),
                currentUserId);

        searchChangePublisher.changed("TASK", taskId);
        auditTrail.legacyUpdated(taskId, existing, title, safePriority);
    }

    @Transactional
    public void updateTask(
            Long taskId,
            String title,
            String descriptionMarkdown,
            String priority,
            Long parentTaskId,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Long currentUserId) {
        updateTask(
                taskId,
                null,
                title,
                descriptionMarkdown,
                priority,
                parentTaskId,
                attributes,
                beginTime,
                endTime,
                currentUserId);
    }

    /** What a PATCH refers to must exist and be visible, and the new parent must not close a cycle. */
    private void checkPatch(Long taskId, MsTaskPatch requested, Long currentUserId) {
        if (requested.projectIdPresent()) {
            access.requireProject(requested.projectId());
        }
        if (requested.parentTaskIdPresent() && requested.parentTaskId() != null) {
            requireParent(taskId, requested.parentTaskId(), currentUserId);
        }
        if (requested.attributesPresent() && requested.attributes() != null) {
            customFieldService.validateAttributes("TASK", requested.attributes());
        }
        if (requested.responsibleUserIdPresent()) {
            access.requireParticipant(currentUserId, requested.responsibleUserId());
        }
        if (requested.executorUserIdsPresent() && requested.executorUserIds() != null) {
            access.requireParticipants(currentUserId, requested.executorUserIds());
        }
        if (requested.observerUserIdsPresent() && requested.observerUserIds() != null) {
            access.requireParticipants(currentUserId, requested.observerUserIds());
        }
    }

    private void requireParent(Long taskId, Long parentTaskId, Long currentUserId) {
        access.find(parentTaskId, currentUserId);
        if (parentTaskId.equals(taskId) || treeRepository.isDescendantOf(parentTaskId, taskId)) {
            throw ApiException.conflict(ErrorCode.TASK_PARENT_CYCLE, "error.task.parent_cycle");
        }
    }
}
