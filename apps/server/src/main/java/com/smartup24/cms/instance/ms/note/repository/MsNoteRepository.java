package com.smartup24.cms.instance.ms.note.repository;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.web.Revisions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
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
            long revision,
            @Nullable Instant archivedAt) {}

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
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision, archived_at
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
        return findVisible(id, new QueryPlan.SqlFragment("", Map.of()));
    }

    /**
     * The note with this id if it lies in the viewer's scope: {@code scope} is the entity's predicate over the alias
     * {@code n} (ADR-0032, 5.1), so another person's note is never selected. An archived note is found too.
     */
    public Optional<NoteRecord> findVisible(Long id, QueryPlan.SqlFragment scope) {
        Map<String, Object> params = new LinkedHashMap<>(scope.params());
        params.put("id", id);
        return jdbcClient
                .sql("""
                select n.id, n.title, n.content_md, n.color, n.is_pinned, n.attributes::text as attributes_str,
                       n.created_by, n.modified_by, n.created_at, n.modified_at, n.revision, n.archived_at
                from ms_notes n
                where n.id = :id
                """ + scope.sql())
                .params(params)
                .query(this::mapNote)
                .optional();
    }

    /**
     * A page of notes by the registry plan ({@code ms.notes}). {@code predicate} is the entity's list predicate
     * (ADR-0032, 5.1 and 5.4): the declared owner scope and, unless the plan asks for the archive, notes in use only —
     * it goes into the same SQL, so a page and its total only ever see those notes. The list is derived from the entity
     * declaration (ADR-0032, 3.4), so its rows carry the record's keys.
     */
    public KeysetPage<NoteRecord> page(QueryPlan plan, QueryPlan.SqlFragment predicate) {
        return new QueryListRepository(jdbcClient).page(plan, this::mapListed, predicate);
    }

    /** A row of the derived list: the columns are named by the record's keys. */
    private NoteRecord mapListed(ResultSet rs, int rowNum) throws SQLException {
        return new NoteRecord(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("contentMd"),
                rs.getString("color"),
                rs.getBoolean("isPinned"),
                jsonColumns.readObject(rs.getString("attributes")),
                rs.getLong("createdBy"),
                rs.getLong("modifiedBy"),
                instant(rs.getTimestamp("createdAt")),
                instant(rs.getTimestamp("modifiedAt")),
                rs.getLong("revision"),
                instant(rs.getTimestamp("archivedAt")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision, archived_at
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
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision, archived_at
                """)
                .param("id", id)
                .param("pinned", pinned)
                .param("userId", userId)
                .query(this::mapNote)
                .optional();
    }

    /**
     * Archives or restores the note in one statement (ADR-0032, 5.4): the row is written only when its state changes
     * and, when a revision is named, only from that revision; the archive keeps who archived it and when. Empty when
     * nothing was written — the state was already the asked one, or the revision is stale.
     */
    public Optional<NoteRecord> setArchived(Long id, boolean archived, Long userId, @Nullable Long expectedRevision) {
        return jdbcClient
                .sql("""
                update ms_notes
                set archived_at = case when cast(:archived as boolean) then clock_timestamp() end,
                    archived_by = case when cast(:archived as boolean) then cast(:userId as bigint) end,
                    modified_by = :userId,
                    modified_at = clock_timestamp(),
                    revision = revision + 1
                where id = :id
                  and (archived_at is not null) <> cast(:archived as boolean)
                  and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                returning id, title, content_md, color, is_pinned, attributes::text as attributes_str, created_by, modified_by, created_at, modified_at, revision, archived_at
                """)
                .param("id", id)
                .param("archived", archived)
                .param("userId", userId)
                .param("expectedRevision", expectedRevision)
                .query(this::mapNote)
                .optional();
    }

    /**
     * Deletes the note; when a revision is named, only from that revision (ADR-0032, 5.3), so a stale screen cannot
     * delete a note changed since it was read. False when nothing was deleted.
     */
    public boolean delete(Long id, @Nullable Long expectedRevision) {
        return jdbcClient
                        .sql("""
                        delete from ms_notes
                        where id = :id and (cast(:expectedRevision as bigint) is null or revision = :expectedRevision)
                        """)
                        .param("id", id)
                        .param("expectedRevision", expectedRevision)
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
                rs.getLong("revision"),
                instant(rs.getTimestamp("archived_at")));
    }
}
