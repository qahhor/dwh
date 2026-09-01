package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.error.CpApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CpDeploymentRepository {

    private final JdbcClient jdbc;

    public CpDeploymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createRequested(long instanceId,
                                UUID releaseId,
                                long generation,
                                UUID previousReleaseId) {
        Optional<UUID> inserted = jdbc.sql("""
                        insert into cp_deployments(
                            instance_id, release_id, generation,
                            previous_release_id, status)
                        values (
                            :instanceId, :releaseId, :generation,
                            :previousReleaseId, 'REQUESTED')
                        on conflict (instance_id, generation) do nothing
                        returning id
                        """)
                .param("instanceId", instanceId)
                .param("releaseId", releaseId)
                .param("generation", generation)
                .param("previousReleaseId", previousReleaseId)
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            return inserted.get();
        }

        CpDeployment existing = findByInstanceAndGeneration(instanceId, generation)
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.CONFLICT,
                        "deployment_request_conflict",
                        "Deployment request conflicted with concurrent state"));
        if (!existing.releaseId().equals(releaseId)
                || !Objects.equals(existing.previousReleaseId(), previousReleaseId)) {
            throw new CpApiException(
                    HttpStatus.CONFLICT,
                    "deployment_request_conflict",
                    "Generation already belongs to another deployment request");
        }
        return existing.id();
    }

    public Optional<CpDeployment> findByInstanceAndGeneration(long instanceId, long generation) {
        return jdbc.sql(DEPLOYMENT_SELECT + " where instance_id = :instanceId and generation = :generation")
                .param("instanceId", instanceId)
                .param("generation", generation)
                .query(CpDeploymentRepository::mapDeployment)
                .optional();
    }

    public CpDeployment lock(UUID deploymentId) {
        return jdbc.sql(DEPLOYMENT_SELECT + " where id = :deploymentId for update")
                .param("deploymentId", deploymentId)
                .query(CpDeploymentRepository::mapDeployment)
                .optional()
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.NOT_FOUND,
                        "deployment_not_found",
                        "Deployment not found"));
    }

    public long nextEventSequence(UUID deploymentId) {
        return jdbc.sql("""
                        select coalesce(max(sequence_no), 0) + 1
                        from cp_deployment_events
                        where deployment_id = :deploymentId
                        """)
                .param("deploymentId", deploymentId)
                .query(Long.class)
                .single();
    }

    public boolean appendEvent(UUID deploymentId,
                               long sequence,
                               String idempotencyKey,
                               CpDeploymentStatus status,
                               String reasonCode,
                               String details) {
        int inserted = jdbc.sql("""
                        insert into cp_deployment_events(
                            deployment_id, sequence_no, idempotency_key,
                            status, reason_code, details)
                        values (
                            :deploymentId, :sequence, :idempotencyKey,
                            :status, :reasonCode, :details)
                        on conflict do nothing
                        """)
                .param("deploymentId", deploymentId)
                .param("sequence", sequence)
                .param("idempotencyKey", idempotencyKey)
                .param("status", status.name())
                .param("reasonCode", reasonCode)
                .param("details", details)
                .update();
        if (inserted == 1) {
            return true;
        }

        List<DeploymentEvent> conflicts = jdbc.sql("""
                        select deployment_id, sequence_no, idempotency_key,
                               status, reason_code, details
                        from cp_deployment_events
                        where (deployment_id = :deploymentId and sequence_no = :sequence)
                           or idempotency_key = :idempotencyKey
                        for update
                        """)
                .param("deploymentId", deploymentId)
                .param("sequence", sequence)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new DeploymentEvent(
                        rs.getObject("deployment_id", UUID.class),
                        rs.getLong("sequence_no"),
                        rs.getString("idempotency_key"),
                        CpDeploymentStatus.valueOf(rs.getString("status")),
                        rs.getString("reason_code"),
                        rs.getString("details")))
                .list();
        DeploymentEvent expected = new DeploymentEvent(
                deploymentId, sequence, idempotencyKey, status, reasonCode, details);
        if (conflicts.size() == 1 && conflicts.getFirst().equals(expected)) {
            return false;
        }
        throw new CpApiException(
                HttpStatus.CONFLICT,
                "deployment_event_conflict",
                "Deployment event replay conflicts with stored content");
    }

    public void updateStatus(UUID deploymentId,
                             CpDeploymentStatus expected,
                             CpDeploymentStatus next,
                             String reasonCode) {
        int updated = jdbc.sql("""
                        update cp_deployments
                        set status = :next,
                            reason_code = :reasonCode,
                            started_at = case
                                when started_at is null and :next = 'PREFLIGHT' then now()
                                else started_at
                            end,
                            finished_at = case
                                when :next in (
                                    'PREFLIGHT_FAILED', 'BACKUP_FAILED', 'SUCCEEDED',
                                    'ROLLED_BACK', 'RECOVERY_REQUIRED', 'CANCELLED')
                                then now()
                                else finished_at
                            end
                        where id = :deploymentId and status = :expected
                        """)
                .param("deploymentId", deploymentId)
                .param("expected", expected.name())
                .param("next", next.name())
                .param("reasonCode", reasonCode)
                .update();
        if (updated != 1) {
            throw new CpApiException(
                    HttpStatus.CONFLICT,
                    "deployment_state_conflict",
                    "Deployment state changed concurrently");
        }
    }

    private static CpDeployment mapDeployment(ResultSet rs, int rowNum) throws SQLException {
        return new CpDeployment(
                rs.getObject("id", UUID.class),
                rs.getLong("instance_id"),
                rs.getObject("release_id", UUID.class),
                rs.getLong("generation"),
                rs.getObject("previous_release_id", UUID.class),
                rs.getString("runner_identity"),
                CpDeploymentStatus.valueOf(rs.getString("status")),
                rs.getString("reason_code"),
                rs.getString("technical_log_reference"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static final String DEPLOYMENT_SELECT = """
            select id, instance_id, release_id, generation, previous_release_id,
                   runner_identity, status, reason_code, technical_log_reference,
                   started_at, finished_at, created_at
            from cp_deployments
            """;

    private record DeploymentEvent(
            UUID deploymentId,
            long sequence,
            String idempotencyKey,
            CpDeploymentStatus status,
            String reasonCode,
            String details) {
    }
}
