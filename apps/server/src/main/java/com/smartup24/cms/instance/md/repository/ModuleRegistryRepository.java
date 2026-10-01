package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ModuleRegistryRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public ModuleRegistryRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "md_installed_modules");
    }

    public record InstalledModuleRecord(
            String code,
            String name,
            String description,
            String version,
            String icon,
            String route,
            boolean isSystem,
            String status,
            int sortOrder,
            Map<String, Object> attributes,
            Instant createdAt,
            Instant modifiedAt,
            long revision) {}

    public List<InstalledModuleRecord> findAll() {
        return jdbcClient.sql("""
                select code, name, description, version, icon, route, is_system, status, sort_order,
                       attributes::text as attributes_str, created_at, modified_at, revision
                from md_installed_modules
                order by sort_order asc, code asc
                """).query(this::mapModule).list();
    }

    public List<InstalledModuleRecord> findActive() {
        return jdbcClient.sql("""
                select code, name, description, version, icon, route, is_system, status, sort_order,
                       attributes::text as attributes_str, created_at, modified_at, revision
                from md_installed_modules
                where status = 'ACTIVE'
                order by sort_order asc, code asc
                """).query(this::mapModule).list();
    }

    public Optional<InstalledModuleRecord> findByCode(String code) {
        return jdbcClient.sql("""
                select code, name, description, version, icon, route, is_system, status, sort_order,
                       attributes::text as attributes_str, created_at, modified_at, revision
                from md_installed_modules
                where code = :code
                """).param("code", code).query(this::mapModule).optional();
    }

    public int updateStatus(String code, String status) {
        return jdbcClient.sql("""
                update md_installed_modules
                set status = :status, modified_at = clock_timestamp(), revision = revision + 1
                where code = :code
                """).param("code", code).param("status", status).update();
    }

    /** Registers a new module; empty when the code is taken already (plan 10/10, item 3.6). */
    public Optional<Long> insertModule(InstalledModuleRecord module) {
        return jdbcClient.sql("""
                insert into md_installed_modules(code, name, description, version, icon, route, is_system, status, sort_order, attributes, created_at, modified_at)
                values(:code, :name, :description, :version, :icon, :route, :isSystem, :status, :sortOrder, cast(:attributes as jsonb), clock_timestamp(), clock_timestamp())
                on conflict (code) do nothing
                returning revision
                """).params(params(module)).query(Long.class).optional();
    }

    /**
     * Replaces the registration of a module made from {@code expectedRevision} and answers its new revision; empty
     * when the revision moved on or the module is gone (plan 10/10, item 3.6). The status and the system flag stay.
     */
    public Optional<Long> replaceModule(InstalledModuleRecord module, long expectedRevision) {
        Map<String, Object> params = new HashMap<>(params(module));
        params.put("expectedRevision", expectedRevision);
        return jdbcClient.sql("""
                update md_installed_modules
                set name = :name,
                    description = :description,
                    version = :version,
                    icon = :icon,
                    route = :route,
                    sort_order = :sortOrder,
                    attributes = cast(:attributes as jsonb),
                    modified_at = clock_timestamp(),
                    revision = revision + 1
                where code = :code and revision = :expectedRevision
                returning revision
                """).params(params).query(Long.class).optional();
    }

    /** Registers the module or replaces its registration whatever its revision: the deprecated form (ADR-0023). */
    public void upsertModule(InstalledModuleRecord module) {
        jdbcClient.sql("""
                insert into md_installed_modules(code, name, description, version, icon, route, is_system, status, sort_order, attributes, created_at, modified_at)
                values(:code, :name, :description, :version, :icon, :route, :isSystem, :status, :sortOrder, cast(:attributes as jsonb), clock_timestamp(), clock_timestamp())
                on conflict (code) do update
                set name = excluded.name,
                    description = excluded.description,
                    version = excluded.version,
                    icon = excluded.icon,
                    route = excluded.route,
                    sort_order = excluded.sort_order,
                    attributes = excluded.attributes,
                    modified_at = clock_timestamp(),
                    revision = md_installed_modules.revision + 1
                """).params(params(module)).update();
    }

    private Map<String, Object> params(InstalledModuleRecord module) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", module.code());
        params.put("name", module.name());
        params.put("description", module.description());
        params.put("version", module.version());
        params.put("icon", module.icon());
        params.put("route", module.route());
        params.put("isSystem", module.isSystem());
        params.put("status", module.status());
        params.put("sortOrder", module.sortOrder());
        params.put("attributes", jsonColumns.object(module.attributes()));
        return params;
    }

    private InstalledModuleRecord mapModule(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> attributes = jsonColumns.readObject(rs.getString("attributes_str"));
        return new InstalledModuleRecord(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("version"),
                rs.getString("icon"),
                rs.getString("route"),
                rs.getBoolean("is_system"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                attributes,
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant()
                        : null,
                rs.getTimestamp("modified_at") != null
                        ? rs.getTimestamp("modified_at").toInstant()
                        : null,
                rs.getLong("revision"));
    }
}
