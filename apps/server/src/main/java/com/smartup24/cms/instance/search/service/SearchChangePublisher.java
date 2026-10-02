package com.smartup24.cms.instance.search.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public search boundary. Revisions commit or roll back with the owning business transaction. The entity runtime's
 * changes reach it through {@link EntitySearchListener}; a module calls it only for a change made outside the runtime.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SearchChangePublisher {
    private final JdbcClient jdbc;

    public SearchChangePublisher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The record of the entity {@code entityType} (its code) changed: its search document is built again. */
    public void changed(String entityType, long entityId) {
        barrier();
        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values (:type,:id,1)
                on conflict(entity_type,entity_id) do update
                set revision=search_projection_versions.revision+1, changed_at=clock_timestamp()
                """).param("type", entityType).param("id", entityId).update();
    }

    private void barrier() {
        jdbc.sql("select id from search_index_state where id=1 for share")
                .query(Integer.class)
                .single();
    }
}
