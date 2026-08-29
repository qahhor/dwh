package com.greenwhite.dwh.instance.ms.task.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MsTaskRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MsTaskRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public TaskRecord create(TaskCreateData data, Long createdBy) {
        String attrsJson = toJson(data.attributes());

        return jdbcClient.sql("""
                insert into ms_tasks (project_id, parent_task_id, title, description_markdown,
                                     status_id, priority, reporter_id, attributes, begin_time,
                                     end_time, created_at, modified_at, created_by, modified_by)
                values (:projectId, :parentTaskId, :title, :descriptionMarkdown,
                        :statusId, :priority, :reporterId, cast(:attributes as jsonb), :beginTime,
                        :endTime, now(), now(), :createdBy, :createdBy)
                returning id, project_id, parent_task_id, title, description_markdown, status_id,
                          priority, reporter_id, attributes::text as attributes_str, begin_time,
                          end_time, resolved_time, created_at, modified_at, created_by, modified_by
                """)
                .param("projectId", data.projectId())
                .param("parentTaskId", data.parentTaskId())
                .param("title", data.title().trim())
                .param("descriptionMarkdown", data.descriptionMarkdown() != null ? data.descriptionMarkdown() : "")
                .param("statusId", data.statusId())
                .param("priority", data.priority() != null ? data.priority() : "medium")
                .param("reporterId", data.reporterId())
                .param("attributes", attrsJson)
                .param("beginTime", data.beginTime() != null ? java.sql.Timestamp.from(data.beginTime()) : null)
                .param("endTime", data.endTime() != null ? java.sql.Timestamp.from(data.endTime()) : null)
                .param("createdBy", createdBy)
                .query(this::mapRecord)
                .single();
    }

    public Optional<TaskRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, project_id, parent_task_id, title, description_markdown, status_id,
                       priority, reporter_id, attributes::text as attributes_str, begin_time,
                       end_time, resolved_time, created_at, modified_at, created_by, modified_by
                from ms_tasks
                where id = :id
                """)
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    public List<TaskRecord> listTasks(int limit, Long afterId, Long projectId, Long statusId,
                                      String priority, String search) {
        StringBuilder sql = new StringBuilder("""
                select id, project_id, parent_task_id, title, description_markdown, status_id,
                       priority, reporter_id, attributes::text as attributes_str, begin_time,
                       end_time, resolved_time, created_at, modified_at, created_by, modified_by
                from ms_tasks
                where 1=1
                """);

        if (afterId != null) {
            sql.append(" and id > :afterId");
        }
        if (projectId != null) {
            sql.append(" and project_id = :projectId");
        }
        if (statusId != null) {
            sql.append(" and status_id = :statusId");
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" and priority = :priority");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" and (title ilike :search or description_markdown ilike :search)");
        }

        sql.append(" order by id asc limit :limit");

        var query = jdbcClient.sql(sql.toString()).param("limit", limit);

        if (afterId != null) query.param("afterId", afterId);
        if (projectId != null) query.param("projectId", projectId);
        if (statusId != null) query.param("statusId", statusId);
        if (priority != null && !priority.isBlank()) query.param("priority", priority);
        if (search != null && !search.isBlank()) query.param("search", "%" + search.trim() + "%");

        return query.query(this::mapRecord).list();
    }

    public void update(Long id, TaskUpdateData data, Long modifiedBy) {
        String attrsJson = data.attributes() != null ? toJson(data.attributes()) : null;

        jdbcClient.sql("""
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
                    attributes = case when :attributes is not null then cast(:attributes as jsonb) else attributes end,
                    modified_at = now(),
                    modified_by = :modifiedBy
                where id = :id
                """)
                .param("id", id)
                .param("title", data.title())
                .param("descriptionMarkdown", data.descriptionMarkdown())
                .param("statusId", data.statusId())
                .param("priority", data.priority())
                .param("projectId", data.projectId())
                .param("parentTaskId", data.parentTaskId())
                .param("beginTime", data.beginTime() != null ? java.sql.Timestamp.from(data.beginTime()) : null)
                .param("endTime", data.endTime() != null ? java.sql.Timestamp.from(data.endTime()) : null)
                .param("resolvedTime", data.resolvedTime() != null ? java.sql.Timestamp.from(data.resolvedTime()) : null)
                .param("attributes", attrsJson)
                .param("modifiedBy", modifiedBy)
                .update();
    }


    public void updateStatus(Long taskId, Long statusId, Instant resolvedTime, Long modifiedBy) {
        jdbcClient.sql("""
                update ms_tasks
                set status_id = :statusId,
                    resolved_time = :resolvedTime,
                    modified_at = now(),
                    modified_by = :modifiedBy
                where id = :taskId
                """)
                .param("taskId", taskId)
                .param("statusId", statusId)
                .param("resolvedTime", resolvedTime != null ? java.sql.Timestamp.from(resolvedTime) : null)
                .param("modifiedBy", modifiedBy)
                .update();
    }

    public List<TaskRecord> findSubtasks(Long parentTaskId) {
        return jdbcClient.sql("""
                select id, project_id, parent_task_id, title, description_markdown, status_id,
                       priority, reporter_id, attributes::text as attributes_str, begin_time,
                       end_time, resolved_time, created_at, modified_at, created_by, modified_by
                from ms_tasks
                where parent_task_id = :parentTaskId
                order by id asc
                """)
                .param("parentTaskId", parentTaskId)
                .query(this::mapRecord)
                .list();
    }

    public List<TaskRecord> findAncestorChain(Long taskId) {
        return jdbcClient.sql("""
                with recursive ancestors as (
                    select id, project_id, parent_task_id, title, description_markdown, status_id,
                           priority, reporter_id, attributes::text as attributes_str, begin_time,
                           end_time, resolved_time, created_at, modified_at, created_by, modified_by, 1 as depth
                    from ms_tasks
                    where id = (select parent_task_id from ms_tasks where id = :taskId)
                    union all
                    select t.id, t.project_id, t.parent_task_id, t.title, t.description_markdown, t.status_id,
                           t.priority, t.reporter_id, t.attributes::text as attributes_str, t.begin_time,
                           t.end_time, t.resolved_time, t.created_at, t.modified_at, t.created_by, t.modified_by, a.depth + 1
                    from ms_tasks t
                    join ancestors a on a.parent_task_id = t.id
                )
                select id, project_id, parent_task_id, title, description_markdown, status_id,
                       priority, reporter_id, attributes_str, begin_time,
                       end_time, resolved_time, created_at, modified_at, created_by, modified_by
                from ancestors
                order by depth desc
                """)
                .param("taskId", taskId)
                .query(this::mapRecord)
                .list();
    }

    public boolean isDescendantOf(Long potentialDescendantId, Long ancestorId) {
        // Recursive CTE to check parent tree cycle
        return jdbcClient.sql("""
                with recursive task_tree as (
                    select id, parent_task_id from ms_tasks where id = :potentialDescendantId
                    union all
                    select t.id, t.parent_task_id from ms_tasks t
                    join task_tree tt on tt.parent_task_id = t.id
                )
                select count(*) from task_tree where id = :ancestorId
                """)
                .param("potentialDescendantId", potentialDescendantId)
                .param("ancestorId", ancestorId)
                .query(Integer.class)
                .single() > 0;
    }

    public List<ProjectTaskStats> getProjectTaskStats() {

        return jdbcClient.sql("""
                select p.id as project_id,
                       count(t.id) as total_tasks,
                       count(t.id) filter (where s.is_terminal = false) as active_tasks,
                       count(t.id) filter (where s.is_terminal = true) as done_tasks
                from ms_task_projects p
                left join ms_tasks t on t.project_id = p.id
                left join ms_task_statuses s on s.id = t.status_id
                group by p.id
                """)
                .query((rs, rowNum) -> new ProjectTaskStats(
                        rs.getLong("project_id"),
                        rs.getInt("total_tasks"),
                        rs.getInt("active_tasks"),
                        rs.getInt("done_tasks")
                ))
                .list();
    }

    public record ProjectTaskStats(
            Long projectId,
            int totalTasks,
            int activeTasks,
            int doneTasks
    ) {}


    private TaskRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TaskRecord(
                rs.getLong("id"),
                rs.getObject("project_id") != null ? rs.getLong("project_id") : null,
                rs.getObject("parent_task_id") != null ? rs.getLong("parent_task_id") : null,
                rs.getString("title"),
                rs.getString("description_markdown"),
                rs.getLong("status_id"),
                rs.getString("priority"),
                rs.getLong("reporter_id"),
                parseJson(rs.getString("attributes_str")),
                rs.getTimestamp("begin_time") != null ? rs.getTimestamp("begin_time").toInstant() : null,
                rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toInstant() : null,
                rs.getTimestamp("resolved_time") != null ? rs.getTimestamp("resolved_time").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant(),
                rs.getLong("created_by"),
                rs.getLong("modified_by")
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
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
            Long modifiedBy
    ) {}

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
            Instant endTime
    ) {}

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
            Instant resolvedTime
    ) {}
}

