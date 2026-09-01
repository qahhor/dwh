package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.api.CpBackupReportRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;

@Service
public class CpBackupReportService {

    private final CpBackupReportRepository repository;

    public CpBackupReportService(CpBackupReportRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordBackup(CpInstancePrincipal principal, CpBackupReportRequest request) {
        validateArtifactState(request);
        var result = repository.recordArtifact(
                principal.instanceId(),
                request.backupId(),
                request.status(),
                request.checksumSha256(),
                request.durationSec() == null ? 0 : request.durationSec(),
                request.completedAt().truncatedTo(ChronoUnit.MICROS),
                request.reasonCode());
        if (result == CpBackupReportRepository.RecordResult.CONFLICT) {
            throw new CpApiException(
                    HttpStatus.CONFLICT,
                    "backup_report_conflict",
                    "Backup report identifier is already bound to different content");
        }
    }

    private static void validateArtifactState(CpBackupReportRequest request) {
        boolean uploadedWithoutChecksum = request.status()
                == CpBackupReportRequest.ArtifactStatus.UPLOADED
                && request.checksumSha256() == null;
        boolean failedWithChecksum = request.status()
                == CpBackupReportRequest.ArtifactStatus.FAILED
                && request.checksumSha256() != null;
        if (uploadedWithoutChecksum || failedWithChecksum) {
            throw new CpApiException(
                    HttpStatus.BAD_REQUEST,
                    "backup_report_invalid",
                    "Backup artifact status and checksum are inconsistent");
        }
    }
}
