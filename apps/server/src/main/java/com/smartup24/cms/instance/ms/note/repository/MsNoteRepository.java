package com.smartup24.cms.instance.ms.note.repository;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.web.Revisions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MsNoteRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public MsNoteRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "ms_notes");
    }

    public record NoteRecord(
            Long id,
            String title,
            String contentMd,
            String color,
            boolean isPinned,
            Map<String, Object> attributes,
            Long createdBy,
            Long modifiedBy,
            Instant createdAt,
            Instant modifiedAt,
            long revision) {}

    public NoteRecord create(
            String title,
            String contentMd,
            String color,
            boolean isPinned,
            Map<String, Object> attributes,
            Long userId) {
        String attrsJson = jsonColumns.object(attributes);
        return jdbcClient
                .sql("""
                insert into ms_notes(title, content_md, color, is_pinned, attributes, created_by, modified_by, created_at, modified_at)
                values(:title, :contentMd, :color, :isPinned, cast(:attributes as jsonb), :userId, :userId, clock_timestamp(), clock_timestamp())
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision
                """)
                .param("title", title)
                .param("contentMd", contentMd != null ? contentMd : "")
                .param("color", color != null ? color : "default")
                .param("isPinned", isPinned)
                .param("attributes", attrsJson)
                .param("userId", userId)
                .query(this::mapNote)
                .single();
    }

    public Optional<NoteRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision
                from ms_notes
                where id = :id
                """).param("id", id).query(this::mapNote).optional();
    }

    /**
     * A page of the owner's notes by the registry plan ({@code ms.notes}). Notes are personal (SELF scope): the
     * owner predicate goes into the same SQL, so a page and its total only ever see the owner's notes.
     */
    public KeysetPage<NoteRecord> pageByOwner(QueryPlan plan, Long ownerId) {
        return new QueryListRepository(jdbcClient)
                .page(
                        plan,
                        this::mapNote,
                        new QueryPlan.SqlFragment(" and n.created_by = :ownerId", Map.of("ownerId", ownerId)));
    }

    public NoteRecord update(
            Long id,
            String title,
            String contentMd,
            String color,
            Boolean isPinned,
            Map<String, Object> attributes,
            Long userId,
            @Nullable Long expectedRevision) {
        String attrsJson = attributes == null ? null : jsonColumns.object(attributes);
        return jdbcClient
                .sql("""
                update ms_notes
                set title = coalesce(:title, title),
                    content_md = coalesce(:contentMd, content_md),
                    color = coalesce(:color, color),
                    is_pinned = coalesce(:isPinned, is_pinned),
                    attributes = coalesce(cast(:attributes as jsonb), attributes),
                    modified_by = :userId,
                    modified_at = clock_timestamp(),
                    revision = revision + 1
                where id = :id and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision
                """)
                .param("id", id)
                .param("title", title)
                .param("contentMd", contentMd)
                .param("color", color)
                .param("isPinned", isPinned)
                .param("attributes", attrsJson)
                .param("userId", userId)
                .param("expectedRevision", expectedRevision)
                .query(this::mapNote)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    /**
     * Sets the pin in one statement (plan 10/10, item 3.6): the row is written only when the pin changes, so of
     * concurrent requests exactly those that changed it return a row, and only they are audited.
     */
    public Optional<NoteRecord> setPinned(Long id, boolean pinned, Long userId) {
        return jdbcClient
                .sql("""
                update ms_notes
                set is_pinned = :pinned, modified_by = :userId, modified_at = clock_timestamp(), revision = revision + 1
                where id = :id and is_pinned <> :pinned
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision
                """)
                .param("id", id)
                .param("pinned", pinned)
                .param("userId", userId)
                .query(this::mapNote)
                .optional();
    }

    public boolean delete(Long id) {
        return jdbcClient
                        .sql("delete from ms_notes where id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    private NoteRecord mapNote(ResultSet rs, int rowNum) throws SQLException {
        return new NoteRecord(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content_md"),
                rs.getString("color"),
                rs.getBoolean("is_pinned"),
                jsonColumns.readObject(rs.getString("attributes_str")),
                rs.getLong("created_by"),
                rs.getLong("modified_by"),
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant()
                        : null,
                rs.getTimestamp("modified_at") != null
                        ? rs.getTimestamp("modified_at").toInstant()
                        : null,
                rs.getLong("revision"));
    }
}
