package com.greenwhite.dwh.cp.release;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import com.greenwhite.dwh.cp.security.CpSecurityContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/releases")
public class CpReleaseController {

    private final CpReleaseService service;

    public CpReleaseController(CpReleaseService service) {
        this.service = service;
    }

    @GetMapping
    @CpRequiresRole({CpPref.ROLE_ENGINEER, CpPref.ROLE_ADMIN})
    public ResponseEntity<List<CpRelease>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping("/{releaseId}/revoke")
    @CpRequiresRole(CpPref.ROLE_ADMIN)
    public ResponseEntity<CpRelease> revoke(
            @PathVariable UUID releaseId,
            @Valid @RequestBody RevokeReleaseRequest request) {
        Long actorUserId = CpSecurityContext.currentUserId();
        if (actorUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Control plane login is required");
        }
        return ResponseEntity.ok(service.revoke(releaseId, request.reason(), actorUserId));
    }

    public record RevokeReleaseRequest(
            @NotBlank @Size(max = 500) String reason) {
    }
}
