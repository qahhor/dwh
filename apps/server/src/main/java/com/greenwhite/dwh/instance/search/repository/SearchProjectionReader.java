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
        String source = switch (entityType) {
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
        return jdbc.sql("select v.revision, source.document::text from search_projection_versions v "
                        + "left join lateral (" + source + ") source on true "
                        + "where v.entity_type=:type and v.entity_id=:id")
                .param("type", entityType).param("id", entityId).query((rs, row) -> {
                    long revision = rs.getLong("revision");
                    String json = rs.getString("document");
                    Map<String,Object> document = json == null ? null : new TreeMap<>(mapper.readValue(json, DOCUMENT));
                    Map<String,Object> fingerprintInput = document == null
                            ? new TreeMap<>(Map.of("entity_type", entityType, "entity_id", entityId, "deleted", true))
                            : document;
                    String fingerprint = fingerprint(fingerprintInput);
                    if (document != null) {
                        document.put("_projection_revision", revision);
                        document.put("_projection_fingerprint", fingerprint);
                    }
                    return new Projection(entityType, entityId, revision, document, fingerprint);
                }).optional();
    }

    private String fingerprint(Map<String,Object> canonical) {
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
