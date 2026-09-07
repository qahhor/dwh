package com.greenwhite.dwh.instance.search.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SearchProjectionReader {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private static final TypeReference<Map<String,Object>> DOCUMENT = new TypeReference<>() {};

    public SearchProjectionReader(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Revision and joined source are deliberately read in one PostgreSQL statement snapshot. */
    public Optional<Projection> read(String entityType, long entityId) {
        return readSnapshot(entityType,entityId,false);
    }

    public Optional<Projection> readForReconciliation(String entityType,long entityId) {
        return readSnapshot(entityType,entityId,true);
    }

    public java.util.List<Long> reconciliationIds(String type,long after,int limit) {
        return jdbc.sql("select entity_id from search_projection_versions where entity_type=:type and entity_id>:after "
                + "union select id from "+sourceTable(type)+" where id>:after"+(type.equals("TASK") ? "" : " and state='A'")
                + " order by 1 limit :limit").param("type",type).param("after",after).param("limit",Math.max(1,Math.min(100,limit)))
                .query(Long.class).list();
    }

    private Optional<Projection> readSnapshot(String entityType,long entityId,boolean includeUnversioned) {
        String versions=includeUnversioned
                ? "(select cast(:type as text) as entity_type,cast(:id as bigint) as entity_id,coalesce((select revision from search_projection_versions where entity_type=:type and entity_id=:id),0) as revision)"
                : "search_projection_versions";
        return jdbc.sql("select v.revision, "
                        + "case when octet_length(source.document::text)<=1048320 then source.document::text else null end as document, "
                        + "coalesce(octet_length(source.document::text)>1048320,false) as oversized "
                        + "from "+versions+" v left join lateral (" + source(entityType) + ") source on true "
                        + "where v.entity_type=:type and v.entity_id=:id")
                .param("type", entityType).param("id", entityId).query((rs, row) -> {
                    if (rs.getBoolean("oversized")) throw new DocumentTooLargeException();
                    long revision = rs.getLong("revision");
                    String json = rs.getString("document");
                    Map<String,Object> document = json == null ? null : new TreeMap<>(mapper.readValue(json, DOCUMENT));
                    Map<String,Object> fingerprintInput = document == null
                            ? new TreeMap<>(Map.of("entity_type", entityType, "entity_id", entityId, "deleted", true)) : document;
                    String fingerprint = contentFingerprint(mapper, fingerprintInput);
                    if (document != null) {
                        document.put("_projection_revision", revision);
                        document.put("_projection_fingerprint", fingerprint);
                    }
                    return new Projection(entityType, entityId, revision, document, fingerprint);
                }).optional();
    }

    /** Bounded sample; observed maximum serialized row size plus metadata, scaled by authoritative counts. */
    public long estimateSerializedBytes() {
        long total=0;
        for (String type : java.util.List.of("TASK","PROJECT","USER")) {
            String table=sourceTable(type);
            String filter=type.equals("TASK") ? "" : " where state='A'";
            long estimate=jdbc.sql("select (select count(*) from "+table+filter+") * "
                    + "coalesce(max(least(octet_length(source.document::text),1048576))+256,256) "
                    + "from (select id as entity_id from "+table+filter+" order by id limit 100) v "
                    + "left join lateral ("+source(type)+") source on true").query(Long.class).single();
            total=Math.addExact(total,estimate);
        }
        return total;
    }

    public static String sourceTable(String type) {
        return switch(type) { case "TASK" -> "ms_tasks"; case "PROJECT" -> "ms_task_projects"; case "USER" -> "md_users";
            default -> throw new IllegalArgumentException("Unknown projection type"); };
    }

    private static String source(String entityType) {
        return switch (entityType) {
            case "TASK" -> """
                    select jsonb_strip_nulls(jsonb_build_object(
                        'id',t.id::text,'task_id',t.id,'title',t.title,
                        'description_markdown',coalesce(t.description_markdown,''),
                        'status_name',coalesce(s.name,''),'priority',coalesce(t.priority,'medium'),
                        'project_id',t.project_id,'project_name',coalesce(p.name,''))) as document
                    from ms_tasks t
                    left join ms_task_statuses s on s.id=t.status_id
                    left join ms_task_projects p on p.id=t.project_id
                    where t.id=v.entity_id
                    """;
            case "PROJECT" -> """
                    select jsonb_build_object('id',p.id::text,'project_id',p.id,'name',p.name,
                        'description',coalesce(p.description,''),'state',p.state) as document
                    from ms_task_projects p where p.id=v.entity_id and p.state='A'
                    """;
            case "USER" -> """
                    select jsonb_build_object('id',u.id::text,'user_id',u.id,'name',u.name,
                        'login',u.login,'email',u.email,'phone',coalesce(u.phone,''),'state',u.state) as document
                    from md_users u where u.id=v.entity_id and u.state='A'
                    """;
            default -> throw new IllegalArgumentException("Unknown projection type");
        };
    }

    public static final class DocumentTooLargeException extends RuntimeException {
        public DocumentTooLargeException() { super("DOCUMENT_TOO_LARGE"); }
    }

    public static String contentFingerprint(ObjectMapper mapper, Map<String,Object> document) {
        var canonical = new TreeMap<>(document);
        canonical.remove("_projection_revision");
        canonical.remove("_projection_fingerprint");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public record Projection(String entityType, long entityId, long revision,
                             Map<String,Object> document, String fingerprint) {
        public Projection { if (document != null) document = Map.copyOf(document); }
    }
}
