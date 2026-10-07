package com.smartup24.cms.instance.search.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Invalidates projection versions for records whose scope keys are affected by changes in related entities
 * (ADR-0032, 10.3.1 item C7; plan 10/10, item 5.8).
 * Reads other modules only through their published views (ADR-0026).
 */
@Repository
public class SearchScopeInvalidationRepository {

    private final JdbcClient jdbc;

    public SearchScopeInvalidationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks all tasks that user {@code userId} participates in (as creator, reporter, or member) for reindexing,
     * updating their {@code scope_units} in search documents.
     */
    public int invalidateUserTasks(long userId) {
        return jdbc.sql("""
                insert into search_projection_versions(entity_type, entity_id, revision)
                select 'ms.tasks', t.id, 1
                from ms_task_pub_tasks t
                where t.created_by = :userId or t.reporter_id = :userId
                   or exists (select 1 from ms_task_pub_members m where m.task_id = t.id and m.user_id = :userId)
                on conflict(entity_type, entity_id) do update
                set revision = search_projection_versions.revision + 1, changed_at = clock_timestamp()
                """).param("userId", userId).update();
    }

    /**
     * Marks all projects that user {@code userId} participates in (as creator, direct member, or participant of a task
     * in the project) for reindexing, updating their {@code scope_units} in search documents.
     */
    public int invalidateUserProjects(long userId) {
        return jdbc.sql("""
                insert into search_projection_versions(entity_type, entity_id, revision)
                select 'ms.projects', p.id, 1
                from ms_task_pub_projects p
                where p.created_by = :userId
                   or exists (select 1 from ms_task_pub_project_members pm where pm.project_id = p.id and pm.user_id = :userId)
                   or exists (
                       select 1 from ms_task_pub_tasks t
                       where t.project_id = p.id and (
                           t.created_by = :userId or t.reporter_id = :userId
                           or exists (select 1 from ms_task_pub_members m where m.task_id = t.id and m.user_id = :userId)
                       )
                   )
                on conflict(entity_type, entity_id) do update
                set revision = search_projection_versions.revision + 1, changed_at = clock_timestamp()
                """).param("userId", userId).update();
    }

    /**
     * Marks the project that task {@code taskId} belongs to for reindexing, updating the project's {@code scope_users}
     * and {@code scope_units} to reflect the task's participants.
     */
    public int invalidateTaskProject(long taskId) {
        return jdbc.sql("""
                insert into search_projection_versions(entity_type, entity_id, revision)
                select 'ms.projects', t.project_id, 1
                from ms_task_pub_tasks t
                where t.id = :taskId and t.project_id is not null
                on conflict(entity_type, entity_id) do update
                set revision = search_projection_versions.revision + 1, changed_at = clock_timestamp()
                """).param("taskId", taskId).update();
    }
}
