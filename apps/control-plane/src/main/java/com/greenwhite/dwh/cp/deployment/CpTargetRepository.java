package com.greenwhite.dwh.cp.deployment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CpTargetRepository {

    private final JdbcClient jdbc;

    public CpTargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LockedInstance> lockInstance(long instanceId) {
        return jdbc.sql("""
                        select instance.id,
                               instance.deployment_mode,
                               instance.current_release_id,
                               instance.current_config_version,
                               instance.current_generation
                        from cp_instances instance
                        where instance.id = :instanceId
                        for update
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new LockedInstance(
                        rs.getLong("id"),
                        rs.getString("deployment_mode"),
                        rs.getObject("current_release_id", UUID.class),
                        rs.getString("current_config_version"),
                        rs.getLong("current_generation")))
                .optional();
    }

    public long findTargetGeneration(long instanceId) {
        return jdbc.sql("""
                        select generation
                        from cp_instance_targets
                        where instance_id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public void upsert(long instanceId,
                       long generation,
                       AssignTargetCommand command,
                       long requestedBy,
                       Instant requestedAt) {
        MaintenanceWindow window = command.maintenanceWindow();
        jdbc.sql("""
                        insert into cp_instance_targets(
                            instance_id, generation, desired_release_id, config_version,
                            rollout_ring, maintenance_week_of_month,
                            maintenance_day_of_week, maintenance_start,
                            maintenance_duration_minutes, maintenance_timezone,
                            requested_by, requested_at)
                        values (
                            :instanceId, :generation, :releaseId, :configVersion,
                            :rolloutRing, :weekOfMonth,
                            :dayOfWeek, :maintenanceStart,
                            :durationMinutes, :timezone,
                            :requestedBy, :requestedAt)
                        on conflict (instance_id) do update set
                            generation = excluded.generation,
                            desired_release_id = excluded.desired_release_id,
                            config_version = excluded.config_version,
                            rollout_ring = excluded.rollout_ring,
                            maintenance_week_of_month = excluded.maintenance_week_of_month,
                            maintenance_day_of_week = excluded.maintenance_day_of_week,
                            maintenance_start = excluded.maintenance_start,
                            maintenance_duration_minutes = excluded.maintenance_duration_minutes,
                            maintenance_timezone = excluded.maintenance_timezone,
                            requested_by = excluded.requested_by,
                            requested_at = excluded.requested_at
                        """)
                .param("instanceId", instanceId)
                .param("generation", generation)
                .param("releaseId", command.releaseId())
                .param("configVersion", command.configVersion())
                .param("rolloutRing", command.ring().name())
                .param("weekOfMonth", window.weekOfMonth())
                .param("dayOfWeek", window.dayOfWeek())
                .param("maintenanceStart", window.start())
                .param("durationMinutes", window.durationMinutes())
                .param("timezone", window.timezone())
                .param("requestedBy", requestedBy)
                .param("requestedAt", requestedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<CpTarget> findByInstanceId(long instanceId) {
        return jdbc.sql("""
                        select target.instance_id,
                               target.generation,
                               target.desired_release_id,
                               release.version as release_version,
                               release.manifest_digest,
                               release.manifest_location,
                               target.config_version,
                               target.rollout_ring,
                               target.maintenance_week_of_month,
                               target.maintenance_day_of_week,
                               target.maintenance_start,
                               target.maintenance_duration_minutes,
                               target.maintenance_timezone,
                               target.requested_by,
                               target.requested_at,
                               instance.current_release_id,
                               instance.current_config_version,
                               instance.current_generation
                        from cp_instance_targets target
                        join cp_instances instance on instance.id = target.instance_id
                        join cp_releases release on release.id = target.desired_release_id
                        where target.instance_id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .query(CpTargetRepository::mapTarget)
                .optional();
    }

    private static CpTarget mapTarget(ResultSet rs, int rowNum) throws SQLException {
        return new CpTarget(
                rs.getLong("instance_id"),
                rs.getLong("generation"),
                rs.getObject("desired_release_id", UUID.class),
                rs.getString("release_version"),
                rs.getString("manifest_digest"),
                URI.create(rs.getString("manifest_location")),
                rs.getString("config_version"),
                RolloutRing.valueOf(rs.getString("rollout_ring")),
                new MaintenanceWindow(
                        rs.getInt("maintenance_week_of_month"),
                        rs.getInt("maintenance_day_of_week"),
                        rs.getObject("maintenance_start", java.time.LocalTime.class),
                        rs.getInt("maintenance_duration_minutes"),
                        rs.getString("maintenance_timezone")),
                rs.getLong("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                rs.getObject("current_release_id", UUID.class),
                rs.getString("current_config_version"),
                rs.getLong("current_generation"));
    }

    public record LockedInstance(
            long instanceId,
            String deploymentMode,
            UUID currentReleaseId,
            String currentConfigVersion,
            long currentGeneration) {
    }
}
