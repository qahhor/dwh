package com.greenwhite.dwh.instance.ms.notify.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class MsAnnouncementRepository {

    private final JdbcClient jdbcClient;

    public MsAnnouncementRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AnnouncementRecord> getActiveUnreadAnnouncements(Long userId, String language) {
        return jdbcClient.sql("""
                select a.id,
                       coalesce(a.title_json ->> :lang, a.title_json ->> 'ru') as title,
                       coalesce(a.body_json ->> :lang, a.body_json ->> 'ru') as body,
                       a.banner_type, a.published_at
                from ms_announcements_cache a
                left join ms_announcement_reads r on r.announcement_id = a.id and r.user_id = :userId
                where a.state = 'published' and r.read_at is null
                order by a.published_at desc
                """)
                .param("userId", userId)
                .param("lang", language != null ? language.toLowerCase() : "ru")
                .query((rs, rowNum) -> new AnnouncementRecord(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("banner_type"),
                        rs.getTimestamp("published_at").toInstant()
                ))
                .list();
    }

    public void markAsRead(Long announcementId, Long userId) {
        jdbcClient.sql("""
                insert into ms_announcement_reads (announcement_id, user_id, read_at)
                values (:announcementId, :userId, now())
                on conflict (announcement_id, user_id) do nothing
                """)
                .param("announcementId", announcementId)
                .param("userId", userId)
                .update();
    }

    public record AnnouncementRecord(
            Long id,
            String title,
            String body,
            String bannerType,
            Instant publishedAt
    ) {}
}
