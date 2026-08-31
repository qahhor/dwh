package com.greenwhite.dwh.instance.md.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MdCustomModuleRepository {

    private final JdbcClient jdbcClient;

    public MdCustomModuleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record CustomModuleRecord(
            Long id,
            String code,
            String name,
            String version,
            String description,
            String category,
            String icon,
            String routePath,
            String entrypointUrl,
            String permissionsJson,
            String settingsSchemaJson,
            String status,
            String rejectionReason,
            String cpTicketId,
            Instant approvedAt,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public List<CustomModuleRecord> findAll() {
        return jdbcClient.sql("""
                select id, code, name, version, description, category, icon,
                       route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       settings_schema_json::text as settings_schema_json,
                       status, rejection_reason, cp_ticket_id, approved_at,
                       created_by, created_at, updated_at
                from md_custom_modules
                order by id asc
                """)
                .query(CustomModuleRecord.class)
                .list();
    }

    public List<CustomModuleRecord> findApprovedActive() {
        return jdbcClient.sql("""
                select id, code, name, version, description, category, icon,
                       route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       settings_schema_json::text as settings_schema_json,
                       status, rejection_reason, cp_ticket_id, approved_at,
                       created_by, created_at, updated_at
                from md_custom_modules
                where status = 'APPROVED'
                order by id asc
                """)
                .query(CustomModuleRecord.class)
                .list();
    }

    public Optional<CustomModuleRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, code, name, version, description, category, icon,
                       route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       settings_schema_json::text as settings_schema_json,
                       status, rejection_reason, cp_ticket_id, approved_at,
                       created_by, created_at, updated_at
                from md_custom_modules
                where id = :id
                """)
                .param("id", id)
                .query(CustomModuleRecord.class)
                .optional();
    }

    public Optional<CustomModuleRecord> findByCode(String code) {
        return jdbcClient.sql("""
                select id, code, name, version, description, category, icon,
                       route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       settings_schema_json::text as settings_schema_json,
                       status, rejection_reason, cp_ticket_id, approved_at,
                       created_by, created_at, updated_at
                from md_custom_modules
                where code = :code
                """)
                .param("code", code)
                .query(CustomModuleRecord.class)
                .optional();
    }

    public CustomModuleRecord create(
            String code, String name, String version, String description, String category,
            String icon, String routePath, String entrypointUrl, String permissionsJson,
            String settingsSchemaJson, Long createdBy) {

        return jdbcClient.sql("""
                insert into md_custom_modules (
                    code, name, version, description, category, icon, route_path,
                    entrypoint_url, permissions_json, settings_schema_json, status, created_by
                ) values (
                    :code, :name, :version, :description, :category, :icon, :routePath,
                    :entrypointUrl, cast(:permissionsJson as jsonb), cast(:settingsSchemaJson as jsonb), 'DRAFT', :createdBy
                )
                returning id, code, name, version, description, category, icon,
                          route_path, entrypoint_url,
                          permissions_json::text as permissions_json,
                          settings_schema_json::text as settings_schema_json,
                          status, rejection_reason, cp_ticket_id, approved_at,
                          created_by, created_at, updated_at
                """)
                .param("code", code)
                .param("name", name)
                .param("version", version)
                .param("description", description)
                .param("category", category)
                .param("icon", icon)
                .param("routePath", routePath)
                .param("entrypointUrl", entrypointUrl)
                .param("permissionsJson", permissionsJson != null ? permissionsJson : "[]")
                .param("settingsSchemaJson", settingsSchemaJson != null ? settingsSchemaJson : "{}")
                .param("createdBy", createdBy)
                .query(CustomModuleRecord.class)
                .single();
    }

    public void updateStatus(Long id, String status, String rejectionReason, String ticketId) {
        jdbcClient.sql("""
                update md_custom_modules
                set status = :status,
                    rejection_reason = :rejectionReason,
                    cp_ticket_id = coalesce(:ticketId, cp_ticket_id),
                    approved_at = (case when :status = 'APPROVED' then now() else approved_at end),
                    updated_at = now()
                where id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("rejectionReason", rejectionReason)
                .param("ticketId", ticketId)
                .update();
    }

    public void delete(Long id) {
        jdbcClient.sql("delete from md_custom_modules where id = :id")
                .param("id", id)
                .update();
    }
}
