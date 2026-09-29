package com.smartup24.cms.instance.ms.task.api;

import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The task PATCH body. A class with setters, not a record: a property the client sent as {@code null} clears the
 * value, a property it left out stays as it is, and only a setter call tells the two apart. Each setter hands its
 * value to the patch builder, which records the presence.
 */
public final class UpdateTaskRequest {
    private final MsTaskPatch.Builder patch = MsTaskPatch.builder();

    public UpdateTaskRequest() {}

    public void setProjectId(Long projectId) {
        patch.projectId(projectId);
    }

    public void setTitle(String title) {
        patch.title(title);
    }

    public void setDescriptionMarkdown(String descriptionMarkdown) {
        patch.descriptionMarkdown(descriptionMarkdown);
    }

    public void setParentTaskId(Long parentTaskId) {
        patch.parentTaskId(parentTaskId);
    }

    public void setPriority(String priority) {
        patch.priority(priority);
    }

    public void setResponsibleUserId(Long responsibleUserId) {
        patch.responsibleUserId(responsibleUserId);
    }

    public void setExecutorUserIds(List<Long> executorUserIds) {
        patch.executorUserIds(executorUserIds);
    }

    public void setObserverUserIds(List<Long> observerUserIds) {
        patch.observerUserIds(observerUserIds);
    }

    public void setAttributes(Map<String, Object> attributes) {
        patch.attributes(attributes);
    }

    public void setBeginTime(Instant beginTime) {
        patch.beginTime(beginTime);
    }

    public void setEndTime(Instant endTime) {
        patch.endTime(endTime);
    }

    public void setExpectedRevision(Long expectedRevision) {
        patch.expectedRevision(expectedRevision);
    }

    public MsTaskPatch toPatch() {
        return patch.build();
    }
}
