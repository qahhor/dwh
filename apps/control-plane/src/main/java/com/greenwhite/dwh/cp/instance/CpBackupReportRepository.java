package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.instance.api.CpBackupReportRequest.ArtifactStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CpBackupReportRepository {

    private final JdbcClient jdbc;

    public CpBackupReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RecordResult recordArtifact(long instanceId,
                                       UUID backupId,
                                       ArtifactStatus status,
                                       String checksumSha256,
                                       int durationSec,
                                       Instant completedAt,
                                       String reasonCode) {
        int inserted = jdbc.sql("""
                        insert into cp_instance_backup_reports(
                            backup_id, instance_id, artifact_status, checksum_sha256,
                            duration_sec, completed_at, reason_code)
                        values (
                            :backupId, :instanceId, :status, :checksumSha256,
                            :durationSec, :completedAt, :reasonCode)
                        on conflict (backup_id) do nothing
                        """)
                .param("backupId", backupId)
                .param("instanceId", instanceId)
                .param("status", status.name())
                .param("checksumSha256", checksumSha256)
                .param("durationSec", durationSec)
                .param("completedAt", dbTime(completedAt))
                .param("reasonCode", reasonCode)
                .update();
        if (inserted == 1) {
            return RecordResult.INSERTED;
        }

        return findByBackupId(backupId)
                .filter(existing -> existing.matches(
                        instanceId,
                        status,
                        checksumSha256,
                        durationSec,
                        completedAt,
                        reasonCode))
                .map(existing -> RecordResult.IDEMPOTENT)
                .orElse(RecordResult.CONFLICT);
    }

    public List<BackupReportView> listReports(int limit) {
        return jdbc.sql("""
                        select report.backup_id, report.instance_id, client.code as client_code,
                               report.artifact_status, report.checksum_sha256,
                               report.duration_sec, report.reason_code, report.completed_at,
                               report.received_at, report.verified_at
                        from cp_instance_backup_reports report
                        join cp_instances instance on instance.id = report.instance_id
                        join cp_clients client on client.id = instance.client_id
                        order by report.received_at desc, report.id desc
                        limit :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new BackupReportView(
                        rs.getObject("backup_id", UUID.class),
                        rs.getLong("instance_id"),
                        rs.getString("client_code"),
                        rs.getString("artifact_status"),
                        rs.getString("checksum_sha256"),
                        rs.getInt("duration_sec"),
                        rs.getString("reason_code"),
                        rs.getTimestamp("completed_at").toInstant(),
                        rs.getTimestamp("received_at").toInstant(),
                        rs.getTimestamp("verified_at") != null
                                ? rs.getTimestamp("verified_at").toInstant()
                                : null))
                .list();
    }

    private Optional<StoredArtifact> findByBackupId(UUID backupId) {
        return jdbc.sql("""
                        select instance_id, artifact_status, checksum_sha256,
                               duration_sec, completed_at, reason_code
                        from cp_instance_backup_reports
                        where backup_id = :backupId
                        """)
                .param("backupId", backupId)
                .query((rs, rowNum) -> new StoredArtifact(
                        rs.getLong("instance_id"),
                        ArtifactStatus.valueOf(rs.getString("artifact_status")),
                        rs.getString("checksum_sha256"),
                        rs.getInt("duration_sec"),
                        rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("reason_code")))
                .optional();
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public enum RecordResult {
        INSERTED,
        IDEMPOTENT,
        CONFLICT
    }

    private record StoredArtifact(long instanceId,
                                  ArtifactStatus status,
                                  String checksumSha256,
                                  int durationSec,
                                  Instant completedAt,
                                  String reasonCode) {

        boolean matches(long expectedInstanceId,
                        ArtifactStatus expectedStatus,
                        String expectedChecksumSha256,
                        int expectedDurationSec,
                        Instant expectedCompletedAt,
                        String expectedReasonCode) {
            return instanceId == expectedInstanceId
                    && status == expectedStatus
                    && java.util.Objects.equals(checksumSha256, expectedChecksumSha256)
                    && durationSec == expectedDurationSec
                    && completedAt.equals(expectedCompletedAt)
                    && java.util.Objects.equals(reasonCode, expectedReasonCode);
        }
    }

    public record BackupReportView(
            UUID backupId,
            long instanceId,
            String clientCode,
            String artifactStatus,
            String checksumSha256,
            int durationSec,
            String reasonCode,
            Instant completedAt,
            Instant receivedAt,
            Instant verifiedAt) {
    }
}
