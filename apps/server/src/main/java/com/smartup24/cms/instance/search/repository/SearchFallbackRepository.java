package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The search over PostgreSQL and the database check of index hits (ADR-0032, 10.3): every query reads the entity's own
 * table with the caller's scope predicate ({@code EntityScopes}), built from the declaration ({@link SearchDocumentSql});
 * all user values remain bound parameters, every query is bounded in rows and time.
 */
@Repository
public class SearchFallbackRepository {
    private static final int MAX_DESCRIPTION_CODE_POINTS = 240;
    private final JdbcClient jdbcClient;

    public SearchFallbackRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * The records of the entity in the caller's scope whose searched fields contain one of the query's variants, a title
     * with the query itself first; one more than {@code limit} tells that there are more.
     */
    @Transactional(readOnly = true, timeout = 2)
    public List<FallbackHit> search(
            SearchEntity entity, String query, List<String> variants, int limit, QueryPlan.SqlFragment scope) {
        List<String> active = variants == null || variants.isEmpty() ? List.of(query) : variants;
        String[] patterns =
                active.stream().map(SearchFallbackRepository::contains).toArray(String[]::new);
        return jdbcClient
                .sql(SearchDocumentSql.matching(entity, scope.sql()))
                .params(scope.params())
                .param("patterns", patterns)
                .param("primary", contains(query))
                .param("limit", limit)
                .query((rs, rowNum) -> hit(entity, rs))
                .list();
    }

    /** The record {@code id} of the entity, when the search finds it and the caller's scope keeps it. */
    @Transactional(readOnly = true, timeout = 2)
    public List<FallbackHit> exact(SearchEntity entity, long id, QueryPlan.SqlFragment scope) {
        return jdbcClient
                .sql(SearchDocumentSql.exact(entity, scope.sql()))
                .params(scope.params())
                .param("id", id)
                .query((rs, rowNum) -> hit(entity, rs))
                .list();
    }

    /**
     * The ids among the index hits that the database still finds in the caller's scope: the index is no authority, a
     * stale document is dropped here (ADR-0032, 10.3).
     */
    @Transactional(readOnly = true, timeout = 2)
    public Set<Long> visible(SearchEntity entity, Collection<Long> ids, QueryPlan.SqlFragment scope) {
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(jdbcClient
                .sql(SearchDocumentSql.visible(entity, scope.sql()))
                .params(scope.params())
                .param("ids", ids.toArray(Long[]::new))
                .query(Long.class)
                .list());
    }

    private static FallbackHit hit(SearchEntity entity, ResultSet rs) throws SQLException {
        long id = rs.getLong(SearchDocumentSql.RECORD_ID);
        List<EntityField> fields = entity.fields();
        String title = rs.getString(fields.getFirst().key());
        String description = "";
        for (EntityField field : fields.subList(1, fields.size())) {
            String value = rs.getString(field.key());
            if (value != null && !value.isBlank()) {
                description = value;
                break;
            }
        }
        return new FallbackHit(
                entity.code(),
                Long.toString(id),
                title == null || title.isBlank() ? "#" + id : title,
                bounded(description),
                entity.targetUrl(id));
    }

    private static String contains(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String bounded(String value) {
        if (value == null) return "";
        int count = value.codePointCount(0, value.length());
        return count <= MAX_DESCRIPTION_CODE_POINTS
                ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_DESCRIPTION_CODE_POINTS));
    }

    public record FallbackHit(String entityType, String id, String title, String description, String targetUrl) {}
}
