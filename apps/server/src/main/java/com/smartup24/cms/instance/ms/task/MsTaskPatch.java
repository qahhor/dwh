package com.smartup24.cms.instance.ms.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Presence-aware values for the public task PATCH contract. Build it with {@link #builder()}: setting a property
 * marks it present, so a {@code null} value clears the column and an untouched property keeps it.
 */
public record MsTaskPatch(
        boolean projectIdPresent,
        Long projectId,
        boolean titlePresent,
        String title,
        boolean descriptionMarkdownPresent,
        String descriptionMarkdown,
        boolean parentTaskIdPresent,
        Long parentTaskId,
        boolean priorityPresent,
        String priority,
        boolean responsibleUserIdPresent,
        Long responsibleUserId,
        boolean executorUserIdsPresent,
        List<Long> executorUserIds,
        boolean observerUserIdsPresent,
        List<Long> observerUserIds,
        boolean attributesPresent,
        Map<String, Object> attributes,
        boolean beginTimePresent,
        Instant beginTime,
        boolean endTimePresent,
        Instant endTime,
        boolean expectedRevisionPresent,
        Long expectedRevision) {

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the properties a caller sets; every setter marks its property present. */
    public static final class Builder {
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

        private Builder() {}

        public Builder projectId(Long value) {
            projectIdPresent = true;
            projectId = value;
            return this;
        }

        public Builder title(String value) {
            titlePresent = true;
            title = value;
            return this;
        }

        public Builder descriptionMarkdown(String value) {
            descriptionMarkdownPresent = true;
            descriptionMarkdown = value;
            return this;
        }

        public Builder parentTaskId(Long value) {
            parentTaskIdPresent = true;
            parentTaskId = value;
            return this;
        }

        public Builder priority(String value) {
            priorityPresent = true;
            priority = value;
            return this;
        }

        public Builder responsibleUserId(Long value) {
            responsibleUserIdPresent = true;
            responsibleUserId = value;
            return this;
        }

        public Builder executorUserIds(List<Long> value) {
            executorUserIdsPresent = true;
            executorUserIds = value;
            return this;
        }

        public Builder observerUserIds(List<Long> value) {
            observerUserIdsPresent = true;
            observerUserIds = value;
            return this;
        }

        public Builder attributes(Map<String, Object> value) {
            attributesPresent = true;
            attributes = value;
            return this;
        }

        public Builder beginTime(Instant value) {
            beginTimePresent = true;
            beginTime = value;
            return this;
        }

        public Builder endTime(Instant value) {
            endTimePresent = true;
            endTime = value;
            return this;
        }

        public Builder expectedRevision(Long value) {
            expectedRevisionPresent = true;
            expectedRevision = value;
            return this;
        }

        public MsTaskPatch build() {
            return new MsTaskPatch(
                    projectIdPresent,
                    projectId,
                    titlePresent,
                    title,
                    descriptionMarkdownPresent,
                    descriptionMarkdown,
                    parentTaskIdPresent,
                    parentTaskId,
                    priorityPresent,
                    priority,
                    responsibleUserIdPresent,
                    responsibleUserId,
                    executorUserIdsPresent,
                    executorUserIds,
                    observerUserIdsPresent,
                    observerUserIds,
                    attributesPresent,
                    attributes,
                    beginTimePresent,
                    beginTime,
                    endTimePresent,
                    endTime,
                    expectedRevisionPresent,
                    expectedRevision);
        }
    }
}
