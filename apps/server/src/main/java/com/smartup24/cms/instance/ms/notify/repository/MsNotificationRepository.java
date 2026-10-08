package com.smartup24.cms.instance.ms.notify.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MsNotificationRepository {

    /** The first key of the advisory locks on a source code, apart from the other lock spaces of the application. */
    static final int SOURCE_LOCK_SPACE = 731_001;

    private final JdbcClient jdbcClient;

    public MsNotificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public NotificationRecord create(
            Long userId, String type, String title, String body, String formLink, String sourceCode) {
        return jdbcClient
                .sql("""
                insert into ms_notifications (user_id, type, title, body, form_link, source_code, is_read, created_at)
                values (:userId, :type, :title, :body, :formLink, :sourceCode, false, now())
                returning id, user_id, type, title, body, form_link, source_code, is_read, created_at
                """)
                .param("userId", userId)
                .param("type", type)
                .param("title", title)
                .param("body", body)
                .param("formLink", formLink)
                .param("sourceCode", sourceCode)
                .query((rs, rowNum) -> new NotificationRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("form_link"),
                        rs.getString("source_code"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    /** Newest first, {@code limit + 1} rows after the position of the page (plan 10/10, item 3.5). */
    public List<NotificationRecord> listUserNotifications(Long userId, TimePage page) {
        TimePage.Position after = page.after();
        return jdbcClient
                .sql("""
                select id, user_id, type, title, body, form_link, source_code, is_read, created_at
                from ms_notifications
                where user_id = :userId
                """ + (after == null ? "" : " and (created_at, id) < (:afterAt, :afterId)") + """
                 order by created_at desc, id desc
                limit :limit
                """)
                .param("userId", userId)
                .param("afterAt", after == null ? null : Timestamp.from(after.at()))
                .param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, rowNum) -> new NotificationRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("form_link"),
                        rs.getString("source_code"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** One notification by its id. */
    public Optional<NotificationRecord> findById(long id) {
        return jdbcClient
                .sql("""
                select id, user_id, type, title, body, form_link, source_code, is_read, created_at
                from ms_notifications
                where id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new NotificationRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("form_link"),
                        rs.getString("source_code"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public int getUnreadCount(Long userId) {
        return jdbcClient.sql("""
                select count(*) from ms_notifications
                where user_id = :userId and not is_read
                """).param("userId", userId).query(Integer.class).single();
    }

    public void markAsRead(Long notificationId, Long userId) {
        jdbcClient
                .sql("""
                update ms_notifications
                set is_read = true
                where id = :notificationId and user_id = :userId
                """)
                .param("notificationId", notificationId)
                .param("userId", userId)
                .update();
    }

    public void markAllAsRead(Long userId) {
        jdbcClient.sql("""
                update ms_notifications
                set is_read = true
                where user_id = :userId and not is_read
                """).param("userId", userId).update();
    }

    /**
     * Serializes, until the current transaction ends, the senders of one source code to one user: of two nodes that
     * decide together, the second waits and then sees the first one's row.
     */
    public void lockSource(Long userId, String sourceCode) {
        jdbcClient
                .sql("select pg_advisory_xact_lock(:space, hashtext(:key))")
                .param("space", SOURCE_LOCK_SPACE)
                .param("key", userId + ":" + sourceCode)
                .query((rs, rowNum) -> true)
                .single();
    }

    public boolean hasRecentNotification(Long userId, String sourceCode, Instant since) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
                select exists(
                    select 1 from ms_notifications
                    where user_id = :userId
                      and source_code = :sourceCode
                      and created_at >= :since
                )
                """)
                .param("userId", userId)
                .param("sourceCode", sourceCode)
                .param("since", java.sql.Timestamp.from(since))
                .query(Boolean.class)
                .single());
    }

    public record NotificationRecord(
            Long id,
            Long userId,
            String type,
            String title,
            String body,
            String formLink,
            String sourceCode,
            boolean isRead,
            Instant createdAt) {}
}
