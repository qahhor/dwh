package com.greenwhite.dwh.instance.ms.notify.repository;

import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementState;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class MsAnnouncementRepository {

    private static final String MANAGED_COLUMNS = """
            id, title_json::text as title_json_text, body_json::text as body_json_text,
            banner_type, state, created_by, created_at, modified_at,
            published_at, archived_at, lock_version
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RowMapper<ManagedAnnouncementRecord> managedMapper = this::mapManaged;

    public MsAnnouncementRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<AnnouncementRecord> getActiveUnreadAnnouncements(Long userId, String language) {
        return jdbcClient.sql("""
                select a.id,
                       coalesce(a.title_json ->> :lang, a.title_json ->> 'ru') as title,
                       coalesce(a.body_json ->> :lang, a.body_json ->> 'ru') as body,
                       a.banner_type, a.published_at
                from ms_announcements a
                left join ms_announcement_reads r on r.announcement_id = a.id and r.user_id = :userId
                where a.state = 'PUBLISHED' and r.read_at is null
                order by a.published_at desc, a.id desc
                """)
                .param("userId", userId)
                .param("lang", normalizedLanguage(language))
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

    public List<ManagedAnnouncementRecord> findAll() {
        return jdbcClient.sql("select " + MANAGED_COLUMNS + " from ms_announcements order by modified_at desc, id desc")
                .query(managedMapper)
                .list();
    }

    public Optional<ManagedAnnouncementRecord> findById(Long id) {
        return jdbcClient.sql("select " + MANAGED_COLUMNS + " from ms_announcements where id = :id")
                .param("id", id)
                .query(managedMapper)
                .optional();
    }

    public ManagedAnnouncementRecord create(
            Map<String, String> titleJson,
            Map<String, String> bodyJson,
            String bannerType,
            Long createdBy) {
        return jdbcClient.sql("""
                        insert into ms_announcements
                            (title_json, body_json, banner_type, state, created_by)
                        values
                            (cast(:titleJson as jsonb), cast(:bodyJson as jsonb), :bannerType, 'DRAFT', :createdBy)
                        returning
                        """ + MANAGED_COLUMNS)
                .param("titleJson", toJson(titleJson))
                .param("bodyJson", toJson(bodyJson))
                .param("bannerType", bannerType)
                .param("createdBy", createdBy)
                .query(managedMapper)
                .single();
    }

    public Optional<ManagedAnnouncementRecord> updateDraft(
            Long id,
            Map<String, String> titleJson,
            Map<String, String> bodyJson,
            String bannerType,
            Long lockVersion) {
        return jdbcClient.sql("""
                        update ms_announcements
                        set title_json = cast(:titleJson as jsonb),
                            body_json = cast(:bodyJson as jsonb),
                            banner_type = :bannerType,
                            modified_at = now(),
                            lock_version = lock_version + 1
                        where id = :id and state = 'DRAFT' and lock_version = :lockVersion
                        returning
                        """ + MANAGED_COLUMNS)
                .param("id", id)
                .param("titleJson", toJson(titleJson))
                .param("bodyJson", toJson(bodyJson))
                .param("bannerType", bannerType)
                .param("lockVersion", lockVersion)
                .query(managedMapper)
                .optional();
    }

    public Optional<ManagedAnnouncementRecord> publish(Long id, Long lockVersion) {
        return jdbcClient.sql("""
                        update ms_announcements
                        set state = 'PUBLISHED',
                            published_at = now(),
                            modified_at = now(),
                            lock_version = lock_version + 1
                        where id = :id and state = 'DRAFT' and lock_version = :lockVersion
                        returning
                        """ + MANAGED_COLUMNS)
                .param("id", id)
                .param("lockVersion", lockVersion)
                .query(managedMapper)
                .optional();
    }

    public Optional<ManagedAnnouncementRecord> archive(Long id, Long lockVersion) {
        return jdbcClient.sql("""
                        update ms_announcements
                        set state = 'ARCHIVED',
                            archived_at = now(),
                            modified_at = now(),
                            lock_version = lock_version + 1
                        where id = :id and state = 'PUBLISHED' and lock_version = :lockVersion
                        returning
                        """ + MANAGED_COLUMNS)
                .param("id", id)
                .param("lockVersion", lockVersion)
                .query(managedMapper)
                .optional();
    }

    private ManagedAnnouncementRecord mapManaged(ResultSet rs, int rowNumber) throws SQLException {
        return new ManagedAnnouncementRecord(
                rs.getLong("id"),
                parseLocalized(rs.getString("title_json_text")),
                parseLocalized(rs.getString("body_json_text")),
                rs.getString("banner_type"),
                AnnouncementState.valueOf(rs.getString("state")),
                nullableLong(rs, "created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant(),
                nullableInstant(rs, "published_at"),
                nullableInstant(rs, "archived_at"),
                rs.getLong("lock_version"));
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Localized announcement content is not serializable", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseLocalized(String json) {
        try {
            Map<String, Object> source = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
            return Collections.unmodifiableMap(result);
        } catch (Exception error) {
            throw new IllegalStateException("Stored announcement content is invalid", error);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalizedLanguage(String language) {
        return language == null || language.isBlank()
                ? "ru"
                : language.trim().toLowerCase(Locale.ROOT);
    }

    public record AnnouncementRecord(
            Long id,
            String title,
            String body,
            String bannerType,
            Instant publishedAt
    ) {
    }

    public record ManagedAnnouncementRecord(
            Long id,
            Map<String, String> titleJson,
            Map<String, String> bodyJson,
            String bannerType,
            AnnouncementState state,
            Long createdBy,
            Instant createdAt,
            Instant modifiedAt,
            Instant publishedAt,
            Instant archivedAt,
            long lockVersion
    ) {
        public ManagedAnnouncementRecord {
            titleJson = Collections.unmodifiableMap(new LinkedHashMap<>(titleJson));
            bodyJson = Collections.unmodifiableMap(new LinkedHashMap<>(bodyJson));
        }
    }
}
