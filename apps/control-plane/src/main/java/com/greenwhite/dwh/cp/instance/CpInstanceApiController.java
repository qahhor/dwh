package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.api.CpCredentialRotationResponse;
import com.greenwhite.dwh.cp.instance.api.CpEnrollmentRequest;
import com.greenwhite.dwh.cp.instance.api.CpEnrollmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instances")
public class CpInstanceApiController {

    private final CpInstanceCredentialService credentials;

    public CpInstanceApiController(CpInstanceCredentialService credentials) {
        this.credentials = credentials;
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
}
