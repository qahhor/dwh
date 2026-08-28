package com.greenwhite.dwh.instance.ms.task.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MsProjectRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MsProjectRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public ProjectRecord create(String name, String description, String state, Map<String, Object> attributes, Long createdBy) {
        String attrsJson = toJson(attributes);

        return jdbcClient.sql("""
                insert into ms_task_projects (name, description, state, attributes, created_at, created_by)
                values (:name, :description, :state, cast(:attributes as jsonb), now(), :createdBy)
                returning id, name, description, state, attributes::text as attributes_str, created_at, created_by
                """)
                .param("name", name.trim())
                .param("description", description)
                .param("state", state != null ? state : "A")
                .param("attributes", attrsJson)
                .param("createdBy", createdBy)
                .query(this::mapRecord)
                .single();
    }

    public Optional<ProjectRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, name, description, state, attributes::text as attributes_str, created_at, created_by
                from ms_task_projects
                where id = :id
                """)
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    public List<ProjectRecord> listProjects(String state) {
        StringBuilder sql = new StringBuilder("""
                select id, name, description, state, attributes::text as attributes_str, created_at, created_by
                from ms_task_projects
                where 1=1
                """);
        if (state != null && !state.isBlank()) {
            sql.append(" and state = :state");
        }
        sql.append(" order by name asc");

        var query = jdbcClient.sql(sql.toString());
        if (state != null && !state.isBlank()) {
            query.param("state", state);
        }
        return query.query(this::mapRecord).list();
    }

    public void update(Long id, String name, String description, String state, Map<String, Object> attributes) {
        String attrsJson = attributes != null ? toJson(attributes) : null;

        jdbcClient.sql("""
                update ms_task_projects
                set name = coalesce(:name, name),
                    description = coalesce(:description, description),
                    state = coalesce(:state, state),
                    attributes = case when :attributes is not null then cast(:attributes as jsonb) else attributes end
                where id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("state", state)
                .param("attributes", attrsJson)
                .update();
    }

    public void addMember(Long projectId, Long userId, String accessKind) {
        jdbcClient.sql("""
                insert into ms_task_project_members (project_id, user_id, access_kind)
                values (:projectId, :userId, :accessKind)
                on conflict (project_id, user_id) do update set access_kind = :accessKind
                """)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("accessKind", accessKind)
                .update();
    }

    public void removeMember(Long projectId, Long userId) {
        jdbcClient.sql("delete from ms_task_project_members where project_id = :projectId and user_id = :userId")
                .param("projectId", projectId)
                .param("userId", userId)
                .update();
    }

    public List<ProjectMemberRecord> getMembers(Long projectId) {
        return jdbcClient.sql("""
                select pm.project_id, pm.user_id, u.name as user_name, u.email as user_email, pm.access_kind
                from ms_task_project_members pm
                join md_users u on u.id = pm.user_id
                where pm.project_id = :projectId
                order by u.name asc
                """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new ProjectMemberRecord(
                        rs.getLong("project_id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getString("user_email"),
                        rs.getString("access_kind")
                ))
                .list();
    }

    private ProjectRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProjectRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("state"),
                parseJson(rs.getString("attributes_str")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
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

    public record ProjectRecord(
            Long id,
            String name,
            String description,
            String state,
            Map<String, Object> attributes,
            Instant createdAt,
            Long createdBy
    ) {}

    public record ProjectMemberRecord(
            Long projectId,
            Long userId,
            String userName,
            String userEmail,
            String accessKind
    ) {}
}
