package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** A task row as the task repositories read and write it: one mapping, so every query returns the same record. */
final class MsTaskRows {

    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    MsTaskRows(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "ms_tasks");
    }

    TaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(
                rs.getLong("id"),
                rs.getObject("project_id") != null ? rs.getLong("project_id") : null,
                rs.getObject("parent_task_id") != null ? rs.getLong("parent_task_id") : null,
                rs.getString("title"),
                rs.getString("description_markdown"),
                rs.getLong("status_id"),
                rs.getString("priority"),
                rs.getLong("reporter_id"),
                jsonColumns.readObject(rs.getString("attributes_str")),
                instant(rs.getTimestamp("begin_time")),
                instant(rs.getTimestamp("end_time")),
                instant(rs.getTimestamp("resolved_time")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant(),
                rs.getLong("created_by"),
                rs.getLong("modified_by"),
                rs.getObject("revision") != null ? rs.getLong("revision") : 1L);
    }

    /** The attributes of a task as a JSON object, written by the shared JSON columns (plan item 3.11). */
    String attributesJson(Map<String, Object> map) {
        return jsonColumns.object(map);
    }

    static Timestamp timestamp(Instant time) {
        return time != null ? Timestamp.from(time) : null;
    }

    private static Instant instant(Timestamp time) {
        return time != null ? time.toInstant() : null;
    }

    /**
     * An update that matched no row: a stale revision when the caller sent one, otherwise the task is gone.
     */
    static void requireUpdated(Optional<Long> revision, Long expectedRevision) {
        if (revision.isPresent()) {
            return;
        }
        if (expectedRevision != null) {
            throw ApiException.conflict(ErrorCode.TASK_REVISION_CONFLICT, "error.task.revision_conflict");
        }
        throw new ApiException(ErrorCode.TASK_NOT_FOUND);
    }
}
