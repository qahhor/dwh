package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public search boundary. Revisions commit or roll back with the owning business transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SearchChangePublisher {
    private final JdbcClient jdbc;

    public SearchChangePublisher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void changed(String entityType, long entityId) {
        barrier();
        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values (:type,:id,1)
                on conflict(entity_type,entity_id) do update
                set revision=search_projection_versions.revision+1, changed_at=clock_timestamp()
                """).param("type", entityType).param("id", entityId).update();
    }

    public void projectChanged(long projectId) {
        barrier();
        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                select entity_type,entity_id,1 from (
                    select 'PROJECT' as entity_type, cast(:id as bigint) as entity_id
                    union all select 'TASK', id from ms_tasks where project_id=:id
                ) affected order by entity_type,entity_id
                on conflict(entity_type,entity_id) do update
                set revision=search_projection_versions.revision+1, changed_at=clock_timestamp()
                """).param("id", projectId).update();
    }

    /** The tasks in the status, whose documents carry its name, are indexed again (a task keeps its status code). */
    public void statusChanged(long statusId) {
        barrier();
        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                select 'TASK',t.id,1 from ms_tasks t
                join ms_task_statuses s on s.code=t.status_code and s.id=:id
                order by t.id
                on conflict(entity_type,entity_id) do update
                set revision=search_projection_versions.revision+1, changed_at=clock_timestamp()
                """).param("id", statusId).update();
    }

    /**
     * Take before creating/moving a status membership: a task keeps the status code (ADR-0032, 8), so no foreign key
     * locks the status row; SHARE on the status in use with the code closes the phantom fan-out race with a rename.
     */
    public void lockStatusMembership(String statusCode) {
        boolean present = jdbc.sql("select id from ms_task_statuses where code=:code and archived_at is null for share")
                .param("code", statusCode)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!present) throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.search.task_status_not_found");
    }

    private void barrier() {
        jdbc.sql("select id from search_index_state where id=1 for share")
                .query(Integer.class)
                .single();
    }
}
