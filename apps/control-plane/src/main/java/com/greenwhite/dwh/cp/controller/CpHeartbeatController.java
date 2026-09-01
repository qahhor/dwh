package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.instance.CpInstancePrincipal;
import com.greenwhite.dwh.cp.repository.CpFleetRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Приём heartbeat от экземпляров (FR-CP-2).
 *
 * Экземпляр не имеет учётной записи в control plane: он аутентифицируется
 * собственным credential, полученным при одноразовом enrollment (заголовок X-Instance-Token).
 * Связь только исходящая от экземпляра — control plane внутрь не ходит (ADR-0004).
 */
@RestController
@RequestMapping("/api/v1/instances")
public class CpHeartbeatController {

    private final CpFleetRepository fleetRepository;

    public CpHeartbeatController(CpFleetRepository fleetRepository) {
        this.fleetRepository = fleetRepository;
    }

    @PostMapping("/heartbeat")
    @Transactional
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody HeartbeatDto body,
                                                         Authentication authentication) {
        CpInstancePrincipal principal = (CpInstancePrincipal) authentication.getPrincipal();
        Long instanceId = principal.instanceId();
        fleetRepository.recordHeartbeat(instanceId, body.appVersion(), body.schemaVersion(), body.metrics());
        var license = fleetRepository.findInstanceLicense(instanceId).orElse(null);
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "instanceId", instanceId,
                "licenseStatus", license != null ? license.licenseStatus() : "ACTIVE",
                "resourceProfile", license != null ? license.resourceProfile() : "S",
                "clientCode", license != null ? license.clientCode() : ""));
    }

    public record HeartbeatDto(String appVersion, String schemaVersion, Map<String, Object> metrics) {}
}
