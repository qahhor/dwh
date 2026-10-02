package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the project hooks, member actions and reads of the module need besides the project record, which the general
 * runtime keeps ({@code ms.projects}, ADR-0032, 8): whether a name is taken, the members of a project, and which of
 * some projects a viewer may see.
 */
@Repository
public class MsProjectRepository {

    private final JdbcClient jdbcClient;

    public MsProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Whether a project in use other than {@code exceptId} already has the name. */
    public boolean nameTaken(String name, long exceptId) {
        return jdbcClient
                .sql("select exists (select 1 from ms_task_projects where name = :name and archived_at is null"
                        + " and id <> :exceptId)")
                .param("name", name)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    /** Of {@code ids}, the projects the predicate lets the viewer see (its alias is {@code p}, ADR-0013). */
    public List<Long> visible(Set<Long> ids, ScopeFilter scope) {
        var query = jdbcClient
                .sql("select p.id from ms_task_projects p where p.id in (:ids)" + scope.sql() + " order by p.id")
                .param("ids", List.copyOf(ids));
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(Long.class).list();
    }

    /** Adds the user to the project, or changes their access; true when anything changed. */
    public boolean addMember(long projectId, long userId, String accessKind) {
        return jdbcClient
                        .sql("""
                insert into ms_task_project_members (project_id, user_id, access_kind)
                values (:projectId, :userId, :accessKind)
                on conflict (project_id, user_id) do update set access_kind = :accessKind
                where ms_task_project_members.access_kind <> :accessKind
                """)
                        .param("projectId", projectId)
                        .param("userId", userId)
                        .param("accessKind", accessKind)
                        .update()
                > 0;
    }

    /** Removes the user from the project; true when they were a member. */
    public boolean removeMember(long projectId, long userId) {
        return jdbcClient
                        .sql("delete from ms_task_project_members where project_id = :projectId and user_id = :userId")
                        .param("projectId", projectId)
                        .param("userId", userId)
                        .update()
                > 0;
    }

    /**
     * The members of a project by name, then user id (plan 10/10, item 3.5): at most {@code limit} after
     * {@code after}, the name and user id of the last member of the previous page (null for the first page).
     */
    public List<ProjectMemberRecord> getMembers(long projectId, @Nullable ProjectMemberRecord after, int limit) {
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

    public record ProjectMemberRecord(
            Long projectId,
            Long userId,
            String userName,
            @Nullable String userEmail,
            @Nullable String accessKind) {}
}
