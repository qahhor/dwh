package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.time.Instant;

/** What a requested task change means for the row: the normalised values and the checks they must pass. */
final class MsTaskPatchRules {

    private MsTaskPatchRules() {}

    /** Unknown and legacy spellings collapse to the four priorities the list and the filters know. */
    static String normalizePriority(String priority) {
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

    /**
     * The columns the PATCH writes. A {@code null} title, description, priority or attributes cannot clear a
     * NOT NULL column, so it counts as not sent; the nullable references and dates are written as sent.
     */
    static MsTaskPatch rowPatch(MsTaskPatch requested) {
        if (requested.titlePresent()
                && requested.title() != null
                && requested.title().isBlank()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "error.task.title_blank");
        }
        var row = MsTaskPatch.builder();
        if (requested.projectIdPresent()) row.projectId(requested.projectId());
        if (requested.titlePresent() && requested.title() != null) row.title(requested.title());
        if (requested.descriptionMarkdownPresent() && requested.descriptionMarkdown() != null) {
            row.descriptionMarkdown(requested.descriptionMarkdown());
        }
        if (requested.parentTaskIdPresent()) row.parentTaskId(requested.parentTaskId());
        if (requested.priorityPresent() && requested.priority() != null) {
            row.priority(normalizePriority(requested.priority()));
        }
        if (requested.attributesPresent() && requested.attributes() != null) row.attributes(requested.attributes());
        if (requested.beginTimePresent()) row.beginTime(requested.beginTime());
        if (requested.endTimePresent()) row.endTime(requested.endTime());
        if (requested.expectedRevisionPresent()) row.expectedRevision(requested.expectedRevision());
        return row.build();
    }

    static void validateDeadline(TaskRecord existing, MsTaskPatch patch) {
        Instant begin = patch.beginTimePresent() ? patch.beginTime() : existing.beginTime();
        Instant end = patch.endTimePresent() ? patch.endTime() : existing.endTime();
        if (begin != null && end != null && begin.isAfter(end)) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "error.task.begin_after_end");
        }
    }
}
