package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public search boundary. Revisions commit or roll back with the owning business transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SearchChangePublisher {
    private final JdbcClient jdbc;

    public SearchChangePublisher(JdbcClient jdbc) { this.jdbc = jdbc; }

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

    public void statusChanged(long statusId) {
        barrier();
        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                select 'TASK',id,1 from ms_tasks where status_id=:id order by id
                on conflict(entity_type,entity_id) do update
                set revision=search_projection_versions.revision+1, changed_at=clock_timestamp()
                """).param("id", statusId).update();
    }

    /**
     * Take before creating/moving a status membership. FK KEY SHARE alone does not
     * conflict with a non-key name update; SHARE closes the phantom fan-out race.
     */
    public void lockStatusMembership(long statusId) {
        jdbc.sql("select id from ms_task_statuses where id=:id for share")
                .param("id", statusId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Статус не найден"));
    }

    private void barrier() {
        jdbc.sql("select id from search_index_state where id=1 for share").query(Integer.class).single();
    }
}
