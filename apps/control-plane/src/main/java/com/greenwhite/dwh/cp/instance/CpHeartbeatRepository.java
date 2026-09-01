package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.instance.api.CpHeartbeatRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Repository
public class CpHeartbeatRepository {

    private final JdbcClient jdbc;

    public CpHeartbeatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public InstanceState recordHeartbeat(long instanceId,
                                         CpHeartbeatRequest request,
                                         String componentHealthJson) {
        CpHeartbeatRequest.StorageTelemetry storage = request.storage();
        CpHeartbeatRequest.BackupTelemetry backup = request.backup();
        CpHeartbeatRequest.AgentTelemetry agents = request.agents();
        CpHeartbeatRequest.CapacityTelemetry capacity = request.capacity();

        jdbc.sql("""
                        insert into cp_instance_heartbeats(
                            instance_id, app_version, schema_version, metrics,
                            release_version, config_version, component_health,
                            storage_used_bytes, storage_quota_bytes,
                            last_backup_at, backup_status, tunnel_status, agent_status,
                            deployment_state, active_users, outbox_pending, outbox_dead_letter)
                        values (
                            :instanceId, :appVersion, :schemaVersion, '{}'::jsonb,
                            :releaseVersion, :configVersion, cast(:componentHealth as jsonb),
                            :storageUsedBytes, :storageQuotaBytes,
                            :lastBackupAt, :backupStatus, :tunnelStatus, :agentStatus,
                            :deploymentState, :activeUsers, :outboxPending, :outboxDeadLetter)
                        """)
                .param("instanceId", instanceId)
                .param("appVersion", request.appVersion())
                .param("schemaVersion", request.schemaVersion())
                .param("releaseVersion", request.releaseVersion())
                .param("configVersion", request.configVersion())
                .param("componentHealth", componentHealthJson)
                .param("storageUsedBytes", storage != null ? storage.usedBytes() : null)
                .param("storageQuotaBytes", storage != null ? storage.quotaBytes() : null)
                .param("lastBackupAt", backup != null && backup.lastCompletedAt() != null
                        ? dbTime(backup.lastCompletedAt()) : null)
                .param("backupStatus", backup != null && backup.status() != null
                        ? backup.status().name() : null)
                .param("tunnelStatus", agents != null && agents.tunnel() != null
                        ? agents.tunnel().name() : null)
                .param("agentStatus", agents != null && agents.telemetry() != null
                        ? agents.telemetry().name() : null)
                .param("deploymentState", request.deploymentState())
                .param("activeUsers", capacity != null ? capacity.activeUsers() : null)
                .param("outboxPending", capacity != null ? capacity.outboxPending() : null)
                .param("outboxDeadLetter", capacity != null ? capacity.outboxDeadLetter() : null)
                .update();

        jdbc.sql("""
                        update cp_instances
                        set last_heartbeat_at = now(),
                            app_version = :appVersion,
                            schema_version = :schemaVersion
                        where id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .param("appVersion", request.appVersion())
                .param("schemaVersion", request.schemaVersion())
                .update();

        return jdbc.sql("""
                        select coalesce(instance.license_status, 'ACTIVE') as license_status,
                               client.resource_profile,
                               coalesce(target.generation, 0) as desired_generation
                        from cp_instances instance
                        join cp_clients client on client.id = instance.client_id
                        left join cp_instance_targets target on target.instance_id = instance.id
                        where instance.id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new InstanceState(
                        rs.getString("license_status"),
                        rs.getString("resource_profile"),
                        rs.getLong("desired_generation")))
                .single();
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    public record InstanceState(
            String licenseStatus,
            String resourceProfile,
            long desiredGeneration) {
    }
}
