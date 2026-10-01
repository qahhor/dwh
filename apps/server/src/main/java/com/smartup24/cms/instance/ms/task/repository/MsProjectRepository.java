package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.common.web.Revisions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MsProjectRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public MsProjectRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "ms_task_projects");
    }

    public ProjectRecord create(
            String name, String description, String state, Map<String, Object> attributes, Long createdBy) {
        String attrsJson = jsonColumns.object(attributes);

        return jdbcClient
                .sql("""
                insert into ms_task_projects (name, description, state, attributes, created_at, created_by)
                values (:name, :description, :state, cast(:attributes as jsonb), now(), :createdBy)
                returning id, name, description, state, attributes::text as attributes_str, created_at, created_by, revision
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
                select id, name, description, state, attributes::text as attributes_str, created_at, created_by, revision
                from ms_task_projects
                where id = :id
                """).param("id", id).query(this::mapRecord).optional();
    }

    /** The project as the viewer may see it (ADR-0013): outside the scope it is as good as missing. */
    public Optional<ProjectRecord> findById(Long id, ScopeFilter scope) {
        var query = jdbcClient.sql("""
                select p.id, p.name, p.description, p.state, p.attributes::text as attributes_str, p.created_at,
                       p.created_by, p.revision
                from ms_task_projects p
                where p.id = :id
                """ + scope.sql()).param("id", id);
        if (scope.bindsUserId()) {
            query = query.param("scopeUserId", scope.userId());
        }
        return query.query(this::mapRecord).optional();
    }

    /** Saves the project made from {@code expectedRevision} (plan item 3.6) and answers its new revision. */
    public long update(
            Long id,
            String name,
            String description,
            String state,
            Map<String, Object> attributes,
            long expectedRevision) {
        String attrsJson = attributes != null ? jsonColumns.object(attributes) : null;

        return jdbcClient
                .sql("""
                update ms_task_projects
                set name = coalesce(:name, name),
                    description = coalesce(:description, description),
                    state = coalesce(:state, state),
                    attributes = coalesce(cast(:attributes as jsonb), attributes),
                    revision = revision + 1
                where id = :id and revision = :expectedRevision
                returning revision
                """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("state", state)
                .param("attributes", attrsJson)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public void addMember(Long projectId, Long userId, String accessKind) {
        jdbcClient
                .sql("""
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
        jdbcClient
                .sql("delete from ms_task_project_members where project_id = :projectId and user_id = :userId")
                .param("projectId", projectId)
                .param("userId", userId)
                .update();
    }

    /**
     * The members of a project by name, then user id (plan 10/10, item 3.5): at most {@code limit} after
     * {@code after}, the name and user id of the last member of the previous page (null for the first page).
     */
    public List<ProjectMemberRecord> getMembers(Long projectId, @Nullable ProjectMemberRecord after, int limit) {
        return jdbcClient
                .sql("""
                select pm.project_id, pm.user_id, u.name as user_name, u.email as user_email, pm.access_kind
                from ms_task_project_members pm
                join md_pub_users u on u.id = pm.user_id
                where pm.project_id = :projectId
                  and (cast(:afterUserId as bigint) is null or (u.name, pm.user_id) > (:afterName, :afterUserId))
                order by u.name asc, pm.user_id asc
                limit :limit
                """)
                .param("projectId", projectId)
                .param("afterName", after != null ? after.userName() : null)
                .param("afterUserId", after != null ? after.userId() : null)
                .param("limit", limit)
                .query((rs, rowNum) -> new ProjectMemberRecord(
                        rs.getLong("project_id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getString("user_email"),
                        rs.getString("access_kind")))
                .list();
    }

    /** Reads a project row; the project list (registry {@code ms.projects}) maps its pages with it. */
    public ProjectRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProjectRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("state"),
                jsonColumns.readObject(rs.getString("attributes_str")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                rs.getLong("revision"));
    }

    public record ProjectRecord(
            Long id,
            String name,
            String description,
            String state,
            Map<String, Object> attributes,
            Instant createdAt,
            Long createdBy,
            long revision) {}

    public record ProjectMemberRecord(
            Long projectId, Long userId, String userName, String userEmail, String accessKind) {}
}
