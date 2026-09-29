package com.smartup24.cms.instance.ms.task.api;

import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The task PATCH body. A class with setters, not a record: a property the client sent as {@code null} clears the
 * value, a property it left out stays as it is, and only a setter call tells the two apart.
 */
public final class UpdateTaskRequest {
    private boolean projectIdPresent;
    private Long projectId;
    private boolean titlePresent;
    private String title;
    private boolean descriptionMarkdownPresent;
    private String descriptionMarkdown;
    private boolean parentTaskIdPresent;
    private Long parentTaskId;
    private boolean priorityPresent;
    private String priority;
    private boolean responsibleUserIdPresent;
    private Long responsibleUserId;
    private boolean executorUserIdsPresent;
    private List<Long> executorUserIds;
    private boolean observerUserIdsPresent;
    private List<Long> observerUserIds;
    private boolean attributesPresent;
    private Map<String, Object> attributes;
    private boolean beginTimePresent;
    private Instant beginTime;
    private boolean endTimePresent;
    private Instant endTime;
    private boolean expectedRevisionPresent;
    private Long expectedRevision;

    public UpdateTaskRequest() {}

    public void setProjectId(Long projectId) {
        this.projectIdPresent = true;
        this.projectId = projectId;
    }

    public void setTitle(String title) {
        this.titlePresent = true;
        this.title = title;
    }

    public void setDescriptionMarkdown(String descriptionMarkdown) {
        this.descriptionMarkdownPresent = true;
        this.descriptionMarkdown = descriptionMarkdown;
    }

    public void setParentTaskId(Long parentTaskId) {
        this.parentTaskIdPresent = true;
        this.parentTaskId = parentTaskId;
    }

    public void setPriority(String priority) {
        this.priorityPresent = true;
        this.priority = priority;
    }

    public void setResponsibleUserId(Long responsibleUserId) {
        this.responsibleUserIdPresent = true;
        this.responsibleUserId = responsibleUserId;
    }

    public void setExecutorUserIds(List<Long> executorUserIds) {
        this.executorUserIdsPresent = true;
        this.executorUserIds = executorUserIds;
    }

    public void setObserverUserIds(List<Long> observerUserIds) {
        this.observerUserIdsPresent = true;
        this.observerUserIds = observerUserIds;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributesPresent = true;
        this.attributes = attributes;
    }

    public void setBeginTime(Instant beginTime) {
        this.beginTimePresent = true;
        this.beginTime = beginTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTimePresent = true;
        this.endTime = endTime;
    }

    public void setExpectedRevision(Long expectedRevision) {
        this.expectedRevisionPresent = true;
        this.expectedRevision = expectedRevision;
    }

    public MsTaskPatch toPatch() {
        return new MsTaskPatch(
                projectIdPresent, projectId,
                titlePresent, title,
                descriptionMarkdownPresent, descriptionMarkdown,
                parentTaskIdPresent, parentTaskId,
                priorityPresent, priority,
                responsibleUserIdPresent, responsibleUserId,
                executorUserIdsPresent, executorUserIds,
                observerUserIdsPresent, observerUserIds,
                attributesPresent, attributes,
                beginTimePresent, beginTime,
                endTimePresent, endTime,
                expectedRevisionPresent, expectedRevision);
    }
}
