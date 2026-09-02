package com.greenwhite.dwh.instance.ms.task.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MsTaskCommentRepository {

    private final JdbcClient jdbcClient;

    public MsTaskCommentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CommentRecord create(Long taskId, Long userId, String textMarkdown, List<UUID> fileIds) {
        var comment = jdbcClient.sql("""
                insert into ms_task_comments (task_id, user_id, text_markdown, created_at)
                values (:taskId, :userId, :textMarkdown, now())
                returning id, task_id, user_id, text_markdown, created_at
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
                        rs.getTimestamp("created_at").toInstant()
                ))
                .single();

        if (fileIds != null && !fileIds.isEmpty()) {
            for (UUID fileId : fileIds) {
                jdbcClient.sql("""
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

    public List<CommentRecord> listComments(Long taskId) {
        return jdbcClient.sql("""
                select c.id, c.task_id, c.user_id, c.text_markdown, c.created_at,
                       coalesce(array_agg(cf.file_id) filter (where cf.file_id is not null), '{}') as file_ids_arr
                from ms_task_comments c
                left join ms_task_comment_files cf on cf.comment_id = c.id
                where c.task_id = :taskId
                group by c.id, c.task_id, c.user_id, c.text_markdown, c.created_at
                order by c.created_at asc
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> {
                    UUID[] arr = (UUID[]) rs.getArray("file_ids_arr").getArray();
                    List<UUID> fileIds = arr != null ? List.of(arr) : List.of();
                    return new CommentRecord(
                            rs.getLong("id"),
                            rs.getLong("task_id"),
                            rs.getLong("user_id"),
                            rs.getString("text_markdown"),
                            fileIds,
                            rs.getTimestamp("created_at").toInstant()
                    );
                })
                .list();
    }

    public record CommentRecord(
            Long id,
            Long taskId,
            Long userId,
            String textMarkdown,
            List<UUID> fileIds,
            Instant createdAt
    ) {}
}
