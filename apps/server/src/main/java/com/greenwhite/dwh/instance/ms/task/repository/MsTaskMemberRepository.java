package com.greenwhite.dwh.instance.ms.task.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MsTaskMemberRepository {

    private final JdbcClient jdbcClient;

    public MsTaskMemberRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void addOrUpdateMember(Long taskId, Long userId, String involveKind, boolean isViewed) {
        jdbcClient.sql("""
                insert into ms_task_members (task_id, user_id, involve_kind, is_viewed)
                values (:taskId, :userId, :involveKind, :isViewed)
                on conflict (task_id, user_id, involve_kind) do update set is_viewed = :isViewed
                """)
                .param("taskId", taskId)
                .param("userId", userId)
                .param("involveKind", involveKind)
                .param("isViewed", isViewed)
                .update();
    }

    public void removeMembersByKind(Long taskId, String involveKind) {
        jdbcClient.sql("delete from ms_task_members where task_id = :taskId and involve_kind = :involveKind")
                .param("taskId", taskId)
                .param("involveKind", involveKind)
                .update();
    }

    public void removeMember(Long taskId, Long userId, String involveKind) {
        jdbcClient.sql("delete from ms_task_members where task_id = :taskId and user_id = :userId and involve_kind = :involveKind")
                .param("taskId", taskId)
                .param("userId", userId)
                .param("involveKind", involveKind)
                .update();
    }

    public void markViewed(Long taskId, Long userId) {
        jdbcClient.sql("""
                update ms_task_members
                set is_viewed = true
                where task_id = :taskId and user_id = :userId
                """)
                .param("taskId", taskId)
                .param("userId", userId)
                .update();
    }

    public List<TaskMemberRecord> getTaskMembers(Long taskId) {
        return jdbcClient.sql("""
                select tm.task_id, tm.user_id, u.name as user_name, u.login as user_login,
                       u.email as user_email, tm.involve_kind, tm.is_viewed
                from ms_task_members tm
                join md_users u on u.id = tm.user_id
                where tm.task_id = :taskId
                order by tm.involve_kind asc, u.name asc
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new TaskMemberRecord(
                        rs.getLong("task_id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getString("user_login"),
                        rs.getString("user_email"),
                        rs.getString("involve_kind"),
                        rs.getBoolean("is_viewed")
                ))
                .list();
    }

    public Optional<Long> getResponsibleUserId(Long taskId) {
        return jdbcClient.sql("""
                select user_id from ms_task_members
                where task_id = :taskId and involve_kind = 'R'
                limit 1
                """)
                .param("taskId", taskId)
                .query(Long.class)
                .optional();
    }

    public record TaskMemberRecord(
            Long taskId,
            Long userId,
            String userName,
            String userLogin,
            String userEmail,
            String involveKind,
            boolean isViewed
    ) {}
}

