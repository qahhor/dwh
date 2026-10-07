package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the search document of a record together with its projection revision (ADR-0032, 10.3): the document is built
 * from the entity's declaration ({@link SearchDocumentSql}); a record the search does not find, or of a type no entity
 * indexes any more, has no document, so its delivery removes it from the index.
 */
@Repository
public class SearchProjectionReader {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    /** Reads the documents the search SQL builds (plan 10/10, item 3.11). */
    private final JsonColumns documents;

    private final SearchEntities entities;
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    /** The largest serialized document the index takes, a little under Typesense's 1 MiB request line. */
    private static final int MAX_DOCUMENT_BYTES = 1_048_320;

    public SearchProjectionReader(JdbcClient jdbc, ObjectMapper mapper, SearchEntities entities) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.documents = new JsonColumns(mapper, "search documents");
        this.entities = entities;
    }

    /** The entity whose records are indexed under the type, if one still declares the search. */
    public Optional<SearchEntity> entity(String entityType) {
        return entities.find(entityType);
    }

    /** Revision and joined source are deliberately read in one PostgreSQL statement snapshot. */
    public Optional<Projection> read(String entityType, long entityId) {
        return readSnapshot(entityType, entityId, false);
    }

    public Optional<Projection> readForReconciliation(String entityType, long entityId) {
        return readSnapshot(entityType, entityId, true);
    }

    /** The ids after {@code after} that hold a projection version or a record the search finds, in order. */
    public List<Long> reconciliationIds(String type, long after, int limit) {
        Optional<SearchEntity> entity = entities.find(type);
        String records = entity.map(found -> " union (" + SearchDocumentSql.ids(found) + ")")
                .orElse("");
        return jdbc.sql("(select entity_id from search_projection_versions where entity_type=:type and entity_id>:after"
                        + " order by entity_id limit :limit)" + records + " order by 1 limit :limit")
                .param("type", type)
                .param("after", after)
                .param("limit", Math.max(1, Math.min(100, limit)))
                .query(Long.class)
                .list();
    }

    private Optional<Projection> readSnapshot(String entityType, long entityId, boolean includeUnversioned) {
        String versions = includeUnversioned
                ? "(select cast(:type as text) as entity_type,cast(:id as bigint) as entity_id,coalesce((select revision from search_projection_versions where entity_type=:type and entity_id=:id),0) as revision)"
                : "search_projection_versions";
        String source = entities.find(entityType)
                .map(SearchDocumentSql::document)
                .orElse("select cast(null as jsonb) as document");
        return jdbc.sql("select v.revision, "
                        + "case when octet_length(source.document::text)<=" + MAX_DOCUMENT_BYTES
                        + " then source.document::text else null end as document, "
                        + "coalesce(octet_length(source.document::text)>" + MAX_DOCUMENT_BYTES
                        + ",false) as oversized "
                        + "from " + versions + " v left join lateral (" + source + ") source on true "
                        + "where v.entity_type=:type and v.entity_id=:id")
                .param("type", entityType)
                .param("id", entityId)
                .query((rs, row) -> {
                    if (rs.getBoolean("oversized")) throw new DocumentTooLargeException();
                    long revision = rs.getLong("revision");
                    String json = rs.getString("document");
                    Map<String, Object> document = json == null ? null : new TreeMap<>(documents.read(json, DOCUMENT));
                    Map<String, Object> fingerprintInput = document == null
                            ? new TreeMap<>(Map.of("entity_type", entityType, "entity_id", entityId, "deleted", true))
                            : document;
                    String fingerprint = contentFingerprint(mapper, fingerprintInput);
                    if (document != null) {
                        document.put("_projection_revision", revision);
                        document.put("_projection_fingerprint", fingerprint);
                    }
                    return new Projection(entityType, entityId, revision, document, fingerprint);
                })
                .optional();
    }

    /** Bounded sample; observed maximum serialized row size plus metadata, scaled by authoritative counts. */
    public long estimateSerializedBytes() {
        long total = 0;
        for (SearchEntity entity : entities.all()) {
            long estimate = jdbc.sql("select (" + SearchDocumentSql.count(entity) + ") * "
                            + "coalesce(max(least(octet_length(source.document::text),1048576))+256,256) "
                            + "from (select entity_id from (" + SearchDocumentSql.ids(entity)
                            + ") sample(entity_id)) v left join lateral (" + SearchDocumentSql.document(entity)
                            + ") source on true")
                    .param("after", 0L)
                    .param("limit", 100)
                    .query(Long.class)
                    .single();
            total = Math.addExact(total, estimate);
        }
        return total;
    }

    public static final class DocumentTooLargeException extends RuntimeException {
        public DocumentTooLargeException() {
            super("DOCUMENT_TOO_LARGE");
        }
    }

    public static String contentFingerprint(ObjectMapper mapper, Map<String, Object> document) {
        var canonical = new TreeMap<>(document);
        canonical.remove("_projection_revision");
        canonical.remove("_projection_fingerprint");
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public record Projection(
            String entityType, long entityId, long revision, Map<String, Object> document, String fingerprint) {
        public Projection {
            if (document != null) document = Map.copyOf(document);
        }
    }
}
