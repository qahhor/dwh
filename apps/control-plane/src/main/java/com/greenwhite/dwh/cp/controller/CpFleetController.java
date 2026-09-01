package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.instance.CpInstanceCredentialService;
import com.greenwhite.dwh.cp.instance.CpInstanceDeploymentMode;
import com.greenwhite.dwh.cp.instance.CpInstanceRegistrationService;
import com.greenwhite.dwh.cp.repository.CpFleetRepository;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import com.greenwhite.dwh.cp.security.CpSecurityContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Реестр клиентов и дашборд флота (FR-CP-1, FR-CP-2, FR-CP-6). */
@RestController
@RequestMapping("/api/v1")
public class CpFleetController {

    private final CpFleetRepository fleetRepository;
    private final CpInstanceRegistrationService registrationService;
    private final CpInstanceCredentialService credentialService;

    public CpFleetController(CpFleetRepository fleetRepository,
                             CpInstanceRegistrationService registrationService,
                             CpInstanceCredentialService credentialService) {
        this.fleetRepository = fleetRepository;
        this.registrationService = registrationService;
        this.credentialService = credentialService;
    }

    // ------------------------------------------------------------- клиенты

    @GetMapping("/clients")
    @CpRequiresRole({CpPref.ROLE_ENGINEER, CpPref.ROLE_EDITOR})
    public ResponseEntity<List<CpFleetRepository.CpClient>> listClients() {
        return ResponseEntity.ok(fleetRepository.listClients());
    }

    @PostMapping("/clients")
    @CpRequiresRole({CpPref.ROLE_ADMIN})
    @Transactional
    public ResponseEntity<Map<String, Object>> createClient(@Valid @RequestBody CreateClientDto body) {
        if (fleetRepository.findClientByCode(body.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Клиент с таким кодом уже существует");
        }
        Long id = fleetRepository.createClient(body.code(), body.name(),
                body.resourceProfile() != null ? body.resourceProfile() : "S");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "code", body.code()));
    }

    // ---------------------------------------------------------- экземпляры

    @PostMapping("/instances")
    @CpRequiresRole({CpPref.ROLE_ADMIN})
    public ResponseEntity<CpInstanceCredentialService.IssuedEnrollment> registerInstance(
            @Valid @RequestBody RegisterInstanceDto body) {
        var enrollment = registrationService.register(
                new CpInstanceRegistrationService.RegistrationCommand(
                        body.clientCode(),
                        body.environment(),
                        body.url(),
                        body.deploymentMode(),
                        body.jurisdiction(),
                        body.cloudProvider(),
                        body.storageProvider(),
                        body.edgeProvider(),
                        body.supportTier()),
                requireOperatorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollment);
    }

    @PostMapping("/instances/{instanceId}/credentials/{credentialId}/revoke")
    @CpRequiresRole({CpPref.ROLE_ADMIN})
    public ResponseEntity<Void> revokeCredential(
            @PathVariable long instanceId,
            @PathVariable long credentialId) {
        credentialService.revoke(instanceId, credentialId, requireOperatorId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/fleet")
    @CpRequiresRole({CpPref.ROLE_ENGINEER})
    public ResponseEntity<Map<String, Object>> fleet() {
        var items = fleetRepository.listFleet();
        long down = items.stream().filter(i -> !"UP".equals(i.health())).count();
        return ResponseEntity.ok(Map.of(
                "items", items,
                "total", items.size(),
                "problems", down,
                "heartbeatTimeoutMinutes", CpPref.HEARTBEAT_TIMEOUT_MINUTES));
    }

    @GetMapping("/backup-checks")
    @CpRequiresRole({CpPref.ROLE_ENGINEER})
    public ResponseEntity<List<CpFleetRepository.CpBackupCheck>> backupChecks(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(fleetRepository.listBackupChecks(Math.min(limit, 500)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/instances/{id}/status")
    @CpRequiresRole({CpPref.ROLE_ADMIN, CpPref.ROLE_ENGINEER})
    @Transactional
    public ResponseEntity<Map<String, Object>> updateStatus(
            @org.springframework.web.bind.annotation.PathVariable("id") Long id,
            @Valid @RequestBody UpdateStatusDto body) {
        fleetRepository.updateInstanceStatus(id, body.status());
        return ResponseEntity.ok(Map.of("instanceId", id, "status", body.status()));
    }

    public record CreateClientDto(@NotBlank String code, @NotBlank String name, String resourceProfile) {}

    public record RegisterInstanceDto(
            @NotBlank String clientCode,
            @NotBlank @Pattern(regexp = "production|staging|dev") String environment,
            @NotNull URI url,
            @NotNull CpInstanceDeploymentMode deploymentMode,
            @NotBlank @Size(max = 64) String jurisdiction,
            @NotBlank @Size(max = 64) String cloudProvider,
            @NotBlank @Size(max = 64) String storageProvider,
            @Size(max = 64) String edgeProvider,
            @NotBlank @Size(max = 64) String supportTier) {
    }

    public record UpdateStatusDto(@NotBlank String status) {}

    private static long requireOperatorId() {
        Long userId = CpSecurityContext.currentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход в control plane");
        }
        return userId;
    }
}
