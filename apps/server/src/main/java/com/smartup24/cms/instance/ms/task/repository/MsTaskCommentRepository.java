package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MsTaskCommentRepository {

    private final JdbcClient jdbcClient;

    public MsTaskCommentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CommentRecord create(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        var comment = jdbcClient
                .sql("""
                with inserted as (
                    insert into ms_task_comments (task_id, user_id, text_markdown, created_at)
                    values (:taskId, :userId, :textMarkdown, now())
                    returning id, task_id, user_id, text_markdown, created_at
                )
                select i.id, i.task_id, i.user_id, i.text_markdown, i.created_at,
                       u.name as user_name, u.login as user_login
                from inserted i
                left join md_pub_users u on u.id = i.user_id
                """)
                .param("taskId", taskId)
                .param("userId", userId)
                .param("textMarkdown", textMarkdown)
                .query((rs, rowNum) -> new CommentRecord(
                        rs.getLong("id"),
                        rs.getLong("task_id"),
                        rs.getLong("user_id"),
                        rs.getString("text_markdown"),
                        List.of(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("user_name"),
                        rs.getString("user_login")))
                .single();

        if (fileIds != null && !fileIds.isEmpty()) {
            for (UUID fileId : fileIds) {
                jdbcClient
                        .sql("""
                        insert into ms_task_comment_files (comment_id, file_id)
                        values (:commentId, :fileId)
                        """)
                        .param("commentId", comment.id())
                        .param("fileId", fileId)
                        .update();
            }
        }

        return comment;
    }

    /** Oldest first, {@code limit + 1} comments after the position of the page (plan 10/10, item 3.5). */
    public List<CommentRecord> listComments(Long taskId, TimePage page) {
        TimePage.Position after = page.after();
        return jdbcClient
                .sql("""
                select c.id, c.task_id, c.user_id, c.text_markdown, c.created_at,
                       u.name as user_name, u.login as user_login,
                       coalesce(array_agg(cf.file_id) filter (where cf.file_id is not null), '{}') as file_ids_arr
                from ms_task_comments c
                left join md_pub_users u on u.id = c.user_id
                left join ms_task_comment_files cf on cf.comment_id = c.id
                where c.task_id = :taskId
                """ + (after == null ? "" : " and (c.created_at, c.id) > (:afterAt, :afterId)") + """
                 group by c.id, c.task_id, c.user_id, c.text_markdown, c.created_at, u.name, u.login
                order by c.created_at asc, c.id asc
                limit :limit
                """)
                .param("taskId", taskId)
                .param("afterAt", after == null ? null : Timestamp.from(after.at()))
                .param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, rowNum) -> {
                    UUID[] arr = (UUID[]) rs.getArray("file_ids_arr").getArray();
                    List<UUID> fileIds = arr != null ? List.of(arr) : List.of();
                    return new CommentRecord(
                            rs.getLong("id"),
                            rs.getLong("task_id"),
                            rs.getLong("user_id"),
                            rs.getString("text_markdown"),
                            fileIds,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("user_name"),
                            rs.getString("user_login"));
                })
                .list();
    }

    public record CommentRecord(
            Long id,
            Long taskId,
            Long userId,
            String textMarkdown,
            List<UUID> fileIds,
            Instant createdAt,
            String userName,
            String userLogin) {}
}
