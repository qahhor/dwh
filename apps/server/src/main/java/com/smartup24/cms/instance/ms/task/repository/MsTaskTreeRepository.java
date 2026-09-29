package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** The task tree through {@code parent_task_id}: subtasks, the ancestor chain and the cycle check. */
@Repository
public class MsTaskTreeRepository {

    private final JdbcClient jdbcClient;
    private final MsTaskRows rows;

    public MsTaskTreeRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.rows = new MsTaskRows(objectMapper);
    }

    public List<TaskRecord> findSubtasks(Long parentTaskId) {
        return findSubtasks(parentTaskId, ScopeFilter.unrestricted());
    }

    public List<TaskRecord> findSubtasks(Long parentTaskId, ScopeFilter scope) {
        String sql = """
                select t.id, t.project_id, t.parent_task_id, t.title, t.description_markdown, t.status_id,
                       t.priority, t.reporter_id, t.attributes::text as attributes_str, t.begin_time,
                       t.end_time, t.resolved_time, t.created_at, t.modified_at, t.created_by, t.modified_by,
                       t.revision
                from ms_tasks t
                where t.parent_task_id = :parentTaskId
                """ + scope.sql() + " order by t.id asc";
        var query = jdbcClient.sql(sql).param("parentTaskId", parentTaskId);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(rows::map).list();
    }

    public List<TaskRecord> findAncestorChain(Long taskId) {
        return findAncestorChain(taskId, ScopeFilter.unrestricted());
    }

    public List<TaskRecord> findAncestorChain(Long taskId, ScopeFilter scope) {
        String sql = """
                with recursive ancestors(id, depth) as (
                    select root.parent_task_id, 1
                    from ms_tasks root
                    where root.id = :taskId and root.parent_task_id is not null
                    union all
                    select parent.parent_task_id, a.depth + 1
                    from ms_tasks parent
                    join ancestors a on a.id = parent.id
                    where parent.parent_task_id is not null
                )
                select t.id, t.project_id, t.parent_task_id, t.title, t.description_markdown, t.status_id,
                       t.priority, t.reporter_id, t.attributes::text as attributes_str, t.begin_time,
                       t.end_time, t.resolved_time, t.created_at, t.modified_at, t.created_by, t.modified_by,
                       t.revision
                from ancestors a
                join ms_tasks t on t.id = a.id
                where 1=1
                """ + scope.sql() + " order by a.depth desc";
        var query = jdbcClient.sql(sql).param("taskId", taskId);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(rows::map).list();
    }

    public boolean isDescendantOf(Long potentialDescendantId, Long ancestorId) {
        // Recursive CTE to check parent tree cycle
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
