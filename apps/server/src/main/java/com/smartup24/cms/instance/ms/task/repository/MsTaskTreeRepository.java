package com.smartup24.cms.instance.ms.task.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tree of tasks: whether a new parent would close a cycle. */
@Repository
public class MsTaskTreeRepository {

    private final JdbcClient jdbcClient;

    public MsTaskTreeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Whether {@code potentialDescendantId} lies under {@code ancestorId}: walks up its parents to the root. */
    public boolean isDescendantOf(long potentialDescendantId, long ancestorId) {
        return jdbcClient
                        .sql("""
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
                        .single()
                > 0;
    }
}
