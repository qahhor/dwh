package com.greenwhite.dwh.cp.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CpModuleModerationRepository {

    private final JdbcClient jdbcClient;

    public CpModuleModerationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record InstanceModuleRecord(
            Long id,
            Long instanceId,
            String clientCode,
            String moduleCode,
            String name,
            String version,
            String description,
            String category,
            String icon,
            String routePath,
            String entrypointUrl,
            String permissionsJson,
            String status,
            String moderationNotes,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public List<InstanceModuleRecord> findAll() {
        return jdbcClient.sql("""
                select id, instance_id, client_code, module_code, name, version,
                       description, category, icon, route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       status, moderation_notes, reviewed_by, reviewed_at,
                       created_at, updated_at
                from cp_instance_modules
                order by created_at desc
                """)
                .query(InstanceModuleRecord.class)
                .list();
    }

    public Optional<InstanceModuleRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, instance_id, client_code, module_code, name, version,
                       description, category, icon, route_path, entrypoint_url,
                       permissions_json::text as permissions_json,
                       status, moderation_notes, reviewed_by, reviewed_at,
                       created_at, updated_at
                from cp_instance_modules
                where id = :id
                """)
                .param("id", id)
                .query(InstanceModuleRecord.class)
                .optional();
    }

    public InstanceModuleRecord upsertSubmission(
            Long instanceId, String clientCode, String moduleCode, String name,
            String version, String description, String category, String icon,
            String routePath, String entrypointUrl, String permissionsJson) {

        return jdbcClient.sql("""
                insert into cp_instance_modules (
                    instance_id, client_code, module_code, name, version,
                    description, category, icon, route_path, entrypoint_url,
                    permissions_json, status, updated_at
                ) values (
                    :instanceId, :clientCode, :moduleCode, :name, :version,
                    :description, :category, :icon, :routePath, :entrypointUrl,
                    cast(:permissionsJson as jsonb), 'PENDING_APPROVAL', now()
                )
                on conflict (instance_id, module_code) do update set
                    name = excluded.name,
                    version = excluded.version,
                    description = excluded.description,
                    entrypoint_url = excluded.entrypoint_url,
                    permissions_json = excluded.permissions_json,
                    status = 'PENDING_APPROVAL',
                    updated_at = now()
                returning id, instance_id, client_code, module_code, name, version,
                          description, category, icon, route_path, entrypoint_url,
                          permissions_json::text as permissions_json,
                          status, moderation_notes, reviewed_by, reviewed_at,
                          created_at, updated_at
                """)
                .param("instanceId", instanceId)
                .param("clientCode", clientCode)
                .param("moduleCode", moduleCode)
                .param("name", name)
                .param("version", version)
                .param("description", description)
                .param("category", category)
                .param("icon", icon)
                .param("routePath", routePath)
                .param("entrypointUrl", entrypointUrl)
                .param("permissionsJson", permissionsJson != null ? permissionsJson : "[]")
                .query(InstanceModuleRecord.class)
                .single();
    }

    public void updateModerationStatus(Long id, String status, String notes, String reviewedBy) {
        jdbcClient.sql("""
                update cp_instance_modules
                set status = :status,
                    moderation_notes = :notes,
                    reviewed_by = :reviewedBy,
                    reviewed_at = now(),
                    updated_at = now()
                where id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("notes", notes)
                .param("reviewedBy", reviewedBy)
                .update();
    }
}
