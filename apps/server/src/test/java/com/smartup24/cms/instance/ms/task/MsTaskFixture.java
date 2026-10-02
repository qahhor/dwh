package com.smartup24.cms.instance.ms.task;

import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tasks written the way the general runtime and the task hooks write them (ADR-0032, 6.3 and 8), for tests that run
 * without the Spring context: the row read for update and written with its revision, the author's participation, and
 * the search revision in the same transaction, as the change event of the runtime gives it — the steps whose order
 * the search's serialization tests check.
 *
 * @param jdbc      the database
 * @param publisher the search revisions, or null when a test does not look at them
 * @param tx        the transactions; a write joins the caller's when there is one
 */
public record MsTaskFixture(JdbcClient jdbc, @Nullable SearchChangePublisher publisher, TransactionTemplate tx) {

    /** A task created by {@code reporter}, in the initial status, in {@code project} under {@code parent}. */
    public long create(String title, long reporter, @Nullable Long project, @Nullable Long parent) {
        return Objects.requireNonNull(tx.execute(transaction -> {
            long id = jdbc.sql("""
                            insert into ms_tasks (project_id, parent_task_id, title, description_markdown, priority,
                                                  reporter_id, created_by, modified_by)
                            values (:project, :parent, :title, 'body', 'medium', :reporter, :reporter, :reporter)
                            returning id
                            """)
                    .param("project", project)
                    .param("parent", parent)
                    .param("title", title)
                    .param("reporter", reporter)
                    .query(Long.class)
                    .single();
            jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind, is_viewed)"
                            + " values (:task, :user, 'A', true)")
                    .param("task", id)
                    .param("user", reporter)
                    .update();
            if (publisher != null) publisher.changed(MsTaskEntity.CODE, id);
            return id;
        }));
    }

    /** A task created by {@code reporter} without a project or a parent. */
    public long create(String title, long reporter) {
        return create(title, reporter, null, null);
    }

    /** A task renamed by {@code actor} from the revision it has. */
    public void rename(long id, String title, long actor) {
        tx.executeWithoutResult(transaction -> {
            lock(id);
            jdbc.sql("update ms_tasks set title = :title, revision = revision + 1, modified_by = :actor,"
                            + " modified_at = clock_timestamp() where id = :id")
                    .param("title", title)
                    .param("actor", actor)
                    .param("id", id)
                    .update();
            if (publisher != null) publisher.changed(MsTaskEntity.CODE, id);
        });
    }

    /** A task moved to the status with {@code code} as the record action {@code set_status} moves it. */
    public void moveStatus(long id, String code, long actor) {
        tx.executeWithoutResult(transaction -> {
            lock(id);
            jdbc.sql("update ms_tasks set status_code = :code, revision = revision + 1, modified_by = :actor,"
                            + " modified_at = clock_timestamp() where id = :id")
                    .param("code", code)
                    .param("actor", actor)
                    .param("id", id)
                    .update();
            if (publisher != null) publisher.changed(MsTaskEntity.CODE, id);
        });
    }

    /** Step 4 of the runtime: the row locked for the rest of the transaction; a missing task fails the test. */
    private void lock(long id) {
        jdbc.sql("select id from ms_tasks where id = :id for update")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
