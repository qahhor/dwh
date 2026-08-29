package com.greenwhite.dwh.cp.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Объявления платформы (FR-CP-5): черновик → публикация → архив. */
@Repository
public class CpAnnouncementRepository {

    private final JdbcClient jdbc;

    public CpAnnouncementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Long create(String bannerType, Map<String, ContentDto> contents, List<Long> targetClientIds) {
        boolean forAll = targetClientIds == null || targetClientIds.isEmpty();
        Long id = jdbc.sql("""
                        insert into cp_announcements (banner_type, state, is_for_all_clients)
                        values (:type, 'draft', :forAll)
                        returning id
                        """)
                .param("type", bannerType)
                .param("forAll", forAll)
                .query(Long.class)
                .single();

        contents.forEach((lang, c) -> jdbc.sql("""
                        insert into cp_announcement_contents (announcement_id, language, title, body)
                        values (:id, :lang, :title, :body)
                        """)
                .param("id", id).param("lang", lang)
                .param("title", c.title()).param("body", c.body())
                .update());

        // «Для всех» — флаг на объявлении (V003), строк в целях при этом нет
        if (!forAll) {
            for (Long clientId : targetClientIds) {
                jdbc.sql("""
                                insert into cp_announcement_targets (announcement_id, client_id)
                                values (:id, :clientId) on conflict do nothing
                                """)
                        .param("id", id).param("clientId", clientId).update();
            }
        }
        return id;
    }

    public void setState(Long id, String state) {
        jdbc.sql("""
                        update cp_announcements
                        set state = :state,
                            published_at = case when :state = 'published' and published_at is null
                                                then now() else published_at end
                        where id = :id
                        """)
                .param("id", id).param("state", state)
                .update();
    }

    public List<Announcement> list() {
        List<Announcement> result = new ArrayList<>(jdbc.sql("""
                        select a.id, a.banner_type, a.state, a.published_at, a.created_at,
                               case when a.is_for_all_clients then 'все клиенты'
                                    else coalesce(
                                        (select string_agg(c.code, ', ' order by c.code)
                                         from cp_announcement_targets t
                                         join cp_clients c on c.id = t.client_id
                                         where t.announcement_id = a.id),
                                        'нет адресатов')
                               end as targets
                        from cp_announcements a
                        order by a.created_at desc
                        """)
                .query((rs, n) -> new Announcement(
                        rs.getLong("id"),
                        rs.getString("banner_type"),
                        rs.getString("state"),
                        rs.getTimestamp("published_at") != null
                                ? rs.getTimestamp("published_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("targets"),
                        List.of()))
                .list());

        for (int i = 0; i < result.size(); i++) {
            Announcement a = result.get(i);
            result.set(i, new Announcement(a.id(), a.bannerType(), a.state(), a.publishedAt(),
                    a.createdAt(), a.targets(), contentsOf(a.id())));
        }
        return result;
    }

    private List<Content> contentsOf(Long announcementId) {
        return jdbc.sql("""
                        select language, title, body from cp_announcement_contents
                        where announcement_id = :id order by language
                        """)
                .param("id", announcementId)
                .query((rs, n) -> new Content(rs.getString("language"),
                        rs.getString("title"), rs.getString("body")))
                .list();
    }

    public record ContentDto(String title, String body) {}

    public record Content(String language, String title, String body) {}

    public record Announcement(Long id, String bannerType, String state, Instant publishedAt,
                               Instant createdAt, String targets, List<Content> contents) {}
}
