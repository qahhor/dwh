package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Only immutable declarations; record writes stay in EntityRecordStore (ADR-0038). */
@Repository
public class StudioFormRepository {
    private final JdbcClient jdbc;
    private final JsonColumns json;

    public record Stored(long id, String code, Declaration declaration, String checksum) {}

    public StudioFormRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        json = new JsonColumns(mapper, "studio_forms");
    }

    public List<Stored> all() {
        return jdbc.sql("select id, code, declaration::text, checksum from studio_forms order by id limit 101")
                .query((rs, row) -> new Stored(
                        rs.getLong("id"),
                        rs.getString("code"),
                        json.read(rs.getString("declaration"), Declaration.class),
                        rs.getString("checksum")))
                .list();
    }

    public void lockCatalog() {
        jdbc.sql("select pg_advisory_xact_lock(756402005)")
                .query((rs, row) -> true)
                .single();
    }

    public Optional<String> checksum(String code) {
        return jdbc.sql("select checksum from studio_forms where code = :code")
                .param("code", code)
                .query(String.class)
                .optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from studio_forms").query(Long.class).single();
    }

    public long insert(String code, Declaration declaration, String checksum, long userId) {
        return jdbc.sql("insert into studio_forms (code, declaration, checksum, created_by) "
                        + "values (:code, cast(:declaration as jsonb), :checksum, :userId) returning id")
                .param("code", code)
                .param("declaration", json.write(declaration))
                .param("checksum", checksum)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
