package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.api.CpBackupReportRequest;
import com.greenwhite.dwh.cp.instance.api.CpCredentialRotationResponse;
import com.greenwhite.dwh.cp.instance.api.CpEnrollmentRequest;
import com.greenwhite.dwh.cp.instance.api.CpEnrollmentResponse;
import com.greenwhite.dwh.cp.instance.api.CpHeartbeatRequest;
import com.greenwhite.dwh.cp.instance.api.CpHeartbeatResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instances")
public class CpInstanceApiController {

    private final CpInstanceCredentialService credentials;
    private final CpBackupReportService backupReports;
    private final CpHeartbeatService heartbeats;

    public CpInstanceApiController(CpInstanceCredentialService credentials,
                                   CpBackupReportService backupReports,
                                   CpHeartbeatService heartbeats) {
        this.credentials = credentials;
        this.backupReports = backupReports;
        this.heartbeats = heartbeats;
    }

    @PostMapping("/enroll")
    public ResponseEntity<CpEnrollmentResponse> enroll(
            @Valid @RequestBody CpEnrollmentRequest request) {
        var issued = credentials.exchange(request.enrollmentToken());
        return ResponseEntity.ok(new CpEnrollmentResponse(
                issued.instanceId(),
                issued.credential()));
    }

    @PostMapping("/credentials/rotate")
    public ResponseEntity<CpCredentialRotationResponse> rotate(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CpInstancePrincipal principal)) {
            throw new CpApiException(
                    HttpStatus.UNAUTHORIZED,
                    "instance_credential_invalid",
                    "Instance credential is invalid, expired or revoked");
        }
        var issued = credentials.rotate(principal);
        return ResponseEntity.ok(new CpCredentialRotationResponse(
                issued.instanceId(),
                issued.credential(),
                issued.previousValidUntil()));
    }

    @PostMapping("/backup-reports")
    public ResponseEntity<Void> recordBackup(
            @AuthenticationPrincipal CpInstancePrincipal principal,
            @Valid @RequestBody CpBackupReportRequest request) {
        backupReports.recordBackup(principal, request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<CpHeartbeatResponse> heartbeat(
            @AuthenticationPrincipal CpInstancePrincipal principal,
            @Valid @RequestBody CpHeartbeatRequest request) {
        return ResponseEntity.ok(heartbeats.recordHeartbeat(principal, request));
    }
}
