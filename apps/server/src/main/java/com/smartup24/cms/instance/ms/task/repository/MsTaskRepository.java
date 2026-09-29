package com.smartup24.cms.instance.ms.task.repository;

import static com.smartup24.cms.instance.ms.task.repository.MsTaskRows.requireUpdated;
import static com.smartup24.cms.instance.ms.task.repository.MsTaskRows.timestamp;

import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.ms.task.MsTaskPatch;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The task row: create, read one and change it. The tree, the files, the aggregates and the list filters live in
 * their own repositories next to this one.
 */
@Repository
public class MsTaskRepository {

    private final JdbcClient jdbcClient;
    private final MsTaskRows rows;

    public MsTaskRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.rows = new MsTaskRows(objectMapper);
    }

    public TaskRecord create(TaskCreateData data, Long createdBy) {
        String attrsJson = rows.toJson(data.attributes());

        return jdbcClient
                .sql("""
                insert into ms_tasks (project_id, parent_task_id, title, description_markdown,
                                     status_id, priority, reporter_id, attributes, begin_time,
                                     end_time, created_at, modified_at, created_by, modified_by)
                values (:projectId, :parentTaskId, :title, :descriptionMarkdown,
                        :statusId, :priority, :reporterId, cast(:attributes as jsonb), :beginTime,
                        :endTime, now(), now(), :createdBy, :createdBy)
                returning id, project_id, parent_task_id, title, description_markdown, status_id,
                          priority, reporter_id, attributes::text as attributes_str, begin_time,
                          end_time, resolved_time, created_at, modified_at, created_by, modified_by,
                          revision
                """)
                .param("projectId", data.projectId())
                .param("parentTaskId", data.parentTaskId())
                .param("title", data.title().trim())
                .param("descriptionMarkdown", data.descriptionMarkdown() != null ? data.descriptionMarkdown() : "")
                .param("statusId", data.statusId())
                .param("priority", data.priority() != null ? data.priority() : "medium")
                .param("reporterId", data.reporterId())
                .param("attributes", attrsJson)
                .param("beginTime", timestamp(data.beginTime()))
                .param("endTime", timestamp(data.endTime()))
                .param("createdBy", createdBy)
                .query(rows::map)
                .single();
    }

    public Optional<TaskRecord> findById(Long id) {
        return findById(id, ScopeFilter.unrestricted());
    }

    public Optional<TaskRecord> findById(Long id, ScopeFilter scope) {
        String sql = "select " + LIST_COLUMNS + """

                from ms_tasks t
                where t.id = :id
                """ + scope.sql();
        var query = jdbcClient.sql(sql).param("id", id);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(rows::map).optional();
    }

    /** Columns of a task row as {@link #mapRecord} reads them; the registry list {@code ms.tasks} selects them. */
    public static final String LIST_COLUMNS = """
            t.id, t.project_id, t.parent_task_id, t.title, t.description_markdown, t.status_id,
            t.priority, t.reporter_id, t.attributes::text as attributes_str, t.begin_time,
            t.end_time, t.resolved_time, t.created_at, t.modified_at, t.created_by, t.modified_by,
            t.revision""";

    public void update(Long id, TaskUpdateData data, Long modifiedBy) {
        String attrsJson = data.attributes() != null ? rows.toJson(data.attributes()) : null;

        var updated = jdbcClient
                .sql("""
                update ms_tasks
                set title = coalesce(:title, title),
                    description_markdown = coalesce(:descriptionMarkdown, description_markdown),
                    status_id = coalesce(:statusId, status_id),
                    priority = coalesce(:priority, priority),
                    project_id = coalesce(:projectId, project_id),
                    parent_task_id = coalesce(:parentTaskId, parent_task_id),
                    begin_time = coalesce(:beginTime, begin_time),
                    end_time = coalesce(:endTime, end_time),
                    resolved_time = coalesce(:resolvedTime, resolved_time),
                    attributes = case when cast(:attributes as text) is not null then cast(:attributes as jsonb) else attributes end,
                    revision = revision + 1,
                    modified_at = now(),
                    modified_by = :modifiedBy
                where id = :id
                  and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                returning revision
                """)
                .param("id", id)
                .param("title", data.title())
                .param("descriptionMarkdown", data.descriptionMarkdown())
                .param("statusId", data.statusId())
                .param("priority", data.priority())
                .param("projectId", data.projectId())
                .param("parentTaskId", data.parentTaskId())
                .param("beginTime", timestamp(data.beginTime()))
                .param("endTime", timestamp(data.endTime()))
                .param("resolvedTime", timestamp(data.resolvedTime()))
                .param("attributes", attrsJson)
                .param("expectedRevision", data.expectedRevision())
                .param("modifiedBy", modifiedBy)
                .query(Long.class)
                .optional();

        requireUpdated(updated, data.expectedRevision());
    }

    public void patch(Long id, MsTaskPatch patch, Long modifiedBy) {
        String attrsJson = patch.attributes() != null ? rows.toJson(patch.attributes()) : null;

        var updated = jdbcClient
                .sql("""
                update ms_tasks
                set title = case when :titlePresent then :title else title end,
                    description_markdown = case when :descriptionPresent then :descriptionMarkdown else description_markdown end,
                    priority = case when :priorityPresent then :priority else priority end,
                    project_id = case when :projectPresent then :projectId else project_id end,
                    parent_task_id = case when :parentPresent then :parentTaskId else parent_task_id end,
                    begin_time = case when :beginPresent then :beginTime else begin_time end,
                    end_time = case when :endPresent then :endTime else end_time end,
                    attributes = case when :attributesPresent then cast(:attributes as jsonb) else attributes end,
                    revision = revision + 1,
                    modified_at = now(),
                    modified_by = :modifiedBy
                where id = :id
                  and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                returning revision
                """)
                .param("id", id)
                .param("titlePresent", patch.titlePresent())
                .param("title", patch.title())
                .param("descriptionPresent", patch.descriptionMarkdownPresent())
                .param("descriptionMarkdown", patch.descriptionMarkdown())
                .param("priorityPresent", patch.priorityPresent())
                .param("priority", patch.priority())
                .param("projectPresent", patch.projectIdPresent())
                .param("projectId", patch.projectId())
                .param("parentPresent", patch.parentTaskIdPresent())
                .param("parentTaskId", patch.parentTaskId())
                .param("beginPresent", patch.beginTimePresent())
                .param("beginTime", timestamp(patch.beginTime()))
                .param("endPresent", patch.endTimePresent())
                .param("endTime", timestamp(patch.endTime()))
                .param("attributesPresent", patch.attributesPresent())
                .param("attributes", attrsJson)
                .param("expectedRevision", patch.expectedRevision())
                .param("modifiedBy", modifiedBy)
                .query(Long.class)
                .optional();

        requireUpdated(updated, patch.expectedRevision());
    }

    public void updateStatus(Long taskId, Long statusId, Instant resolvedTime, Long modifiedBy) {
        updateStatus(taskId, statusId, resolvedTime, null, modifiedBy);
    }

    public void updateStatus(Long taskId, Long statusId, Instant resolvedTime, Long expectedRevision, Long modifiedBy) {
        var updated = jdbcClient
                .sql("""
                update ms_tasks
                set status_id = :statusId,
                    resolved_time = :resolvedTime,
                    revision = revision + 1,
                    modified_at = now(),
                    modified_by = :modifiedBy
                where id = :taskId
                  and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                returning revision
                """)
                .param("taskId", taskId)
                .param("statusId", statusId)
                .param("resolvedTime", timestamp(resolvedTime))
                .param("expectedRevision", expectedRevision)
                .param("modifiedBy", modifiedBy)
                .query(Long.class)
                .optional();

        requireUpdated(updated, expectedRevision);
    }

    /** Reads a row of {@link #LIST_COLUMNS}; the task list (registry {@code ms.tasks}) maps its pages with it. */
    public TaskRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return rows.map(rs, rowNum);
    }

    public record TaskRecord(
            Long id,
            Long projectId,
            Long parentTaskId,
            String title,
            String descriptionMarkdown,
            Long statusId,
            String priority,
            Long reporterId,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Instant resolvedTime,
            Instant createdAt,
            Instant modifiedAt,
            Long createdBy,
            Long modifiedBy,
            Long revision) {
        public TaskRecord(
                Long id,
                Long projectId,
                Long parentTaskId,
                String title,
                String descriptionMarkdown,
                Long statusId,
                String priority,
                Long reporterId,
                Map<String, Object> attributes,
                Instant beginTime,
                Instant endTime,
                Instant resolvedTime,
                Instant createdAt,
                Instant modifiedAt,
                Long createdBy,
                Long modifiedBy) {
            this(
                    id,
                    projectId,
                    parentTaskId,
                    title,
                    descriptionMarkdown,
                    statusId,
                    priority,
                    reporterId,
                    attributes,
                    beginTime,
                    endTime,
                    resolvedTime,
                    createdAt,
                    modifiedAt,
                    createdBy,
                    modifiedBy,
                    1L);
        }
    }

    public record TaskCreateData(
            Long projectId,
            Long parentTaskId,
            String title,
            String descriptionMarkdown,
            Long statusId,
            String priority,
            Long reporterId,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime) {}

    public record TaskUpdateData(
            Long projectId,
            String title,
            String descriptionMarkdown,
            Long statusId,
            String priority,
            Long parentTaskId,
            Map<String, Object> attributes,
            Instant beginTime,
            Instant endTime,
            Instant resolvedTime,
            Long expectedRevision) {
        public TaskUpdateData(
                Long projectId,
                String title,
                String descriptionMarkdown,
                Long statusId,
                String priority,
                Long parentTaskId,
                Map<String, Object> attributes,
                Instant beginTime,
                Instant endTime,
                Instant resolvedTime) {
            this(
                    projectId,
                    title,
                    descriptionMarkdown,
                    statusId,
                    priority,
                    parentTaskId,
                    attributes,
                    beginTime,
                    endTime,
                    resolvedTime,
                    null);
        }
    }
}
