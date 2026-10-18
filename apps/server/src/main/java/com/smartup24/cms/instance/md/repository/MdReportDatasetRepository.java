package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The report datasets of administrators (ADR-0044). A change names the revision it was made from and matches no row
 * when it is stale; the JSON columns hold the fields, the filter and the measures as the service wrote them.
 */
@Repository
public class MdReportDatasetRepository {
    private static final String SELECT = """
            select d.id, d.code, d.name_ru, d.name_uz, d.name_en, d.entity_code, d.lines, d.fields::text as fields,
                   d.filter::text as filter, d.measures::text as measures, d.revision, d.created_at, d.modified_at
            from md_report_datasets d
            """;

    private final JdbcClient jdbc;

    /** A dataset as stored: its JSON parts as text. */
    public record Row(
            long id,
            String code,
            Labels name,
            String entity,
            @Nullable String lines,
            String fields,
            String filter,
            String measures,
            long revision,
            Instant createdAt,
            Instant modifiedAt) {}

    /** What a change writes: the JSON parts as text. */
    public record Values(
            Labels name, String entity, @Nullable String lines, String fields, String filter, String measures) {}

    public MdReportDatasetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Datasets, the newest first; {@code limit + 1} rows are read to tell whether more follow. */
    public List<Row> page(TimePage page) {
        TimePage.Position after = page.after();
        String where = after == null ? "" : " where (d.created_at, d.id) < (:at, :id)";
        var statement = jdbc.sql(SELECT + where + " order by d.created_at desc, d.id desc limit :limit")
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    public Optional<Row> find(String code) {
        return jdbc.sql(SELECT + " where d.code = :code")
                .param("code", code)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /** The datasets over an entity, the oldest first, at most {@code limit}. */
    public List<Row> over(String entity, int limit) {
        return jdbc.sql(SELECT + " where d.entity_code = :entity order by d.created_at, d.id limit :limit")
                .param("entity", entity)
                .param("limit", limit)
                .query((rs, row) -> row(rs))
                .list();
    }

    public long count() {
        Long count = jdbc.sql("select count(*) from md_report_datasets")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public long countOver(String entity) {
        Long count = jdbc.sql("select count(*) from md_report_datasets where entity_code = :entity")
                .param("entity", entity)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Serializes the creation of datasets against their limits. */
    public void lock() {
        jdbc.sql("select pg_advisory_xact_lock(756402044)").query(Object.class).single();
    }

    public long insert(String code, Values values, long userId) {
        return jdbc.sql("""
                        insert into md_report_datasets
                            (code, name_ru, name_uz, name_en, entity_code, lines, fields, filter, measures,
                             created_by, modified_by)
                        values (:code, :ru, :uz, :en, :entity, :lines, cast(:fields as jsonb), cast(:filter as jsonb),
                                cast(:measures as jsonb), :user, :user)
                        returning id
                        """)
                .param("code", code)
                .params(params(values))
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    /** Writes the dataset when it is still at {@code revision}; false when it is stale or gone. */
    public boolean update(long id, long revision, Values values, long userId) {
        return jdbc.sql("""
                        update md_report_datasets set name_ru = :ru, name_uz = :uz, name_en = :en,
                            entity_code = :entity, lines = :lines, fields = cast(:fields as jsonb),
                            filter = cast(:filter as jsonb), measures = cast(:measures as jsonb),
                            revision = revision + 1, modified_by = :user, modified_at = clock_timestamp()
                        where id = :id and revision = :revision
                        """)
                        .param("id", id)
                        .param("revision", revision)
                        .params(params(values))
                        .param("user", userId)
                        .update()
                == 1;
    }

    public boolean delete(long id, long revision) {
        return jdbc.sql("delete from md_report_datasets where id = :id and revision = :revision")
                        .param("id", id)
                        .param("revision", revision)
                        .update()
                == 1;
    }

    private static java.util.Map<String, Object> params(Values values) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("ru", values.name().ru());
        params.put("uz", values.name().uz());
        params.put("en", values.name().en());
        params.put("entity", values.entity());
        params.put("lines", values.lines());
        params.put("fields", values.fields());
        params.put("filter", values.filter());
        params.put("measures", values.measures());
        return params;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getString("code"),
                new Labels(rs.getString("name_ru"), rs.getString("name_uz"), rs.getString("name_en")),
                rs.getString("entity_code"),
                rs.getString("lines"),
                rs.getString("fields"),
                rs.getString("filter"),
                rs.getString("measures"),
                rs.getLong("revision"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("modified_at", OffsetDateTime.class).toInstant());
    }
}
