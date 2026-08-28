package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.repository.CpFleetRepository;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Приём heartbeat и отчётов о бэкапах от экземпляров (FR-CP-2, FR-CP-6).
 *
 * Экземпляр не имеет учётной записи в control plane: он аутентифицируется
 * собственным токеном, выданным при регистрации (заголовок X-Instance-Token).
 * Связь только исходящая от экземпляра — control plane внутрь не ходит (ADR-0004).
 */
@RestController
@RequestMapping("/api/v1/instances")
public class CpHeartbeatController {

    private static final String TOKEN_HEADER = "X-Instance-Token";

    private final CpFleetRepository fleetRepository;

    public CpHeartbeatController(CpFleetRepository fleetRepository) {
        this.fleetRepository = fleetRepository;
    }

    @PostMapping("/heartbeat")
    @Transactional
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody HeartbeatDto body,
                                                         HttpServletRequest request) {
        Long instanceId = requireInstance(request);
        fleetRepository.recordHeartbeat(instanceId, body.appVersion(), body.schemaVersion(), body.metrics());
        return ResponseEntity.ok(Map.of("accepted", true, "instanceId", instanceId));
    }

    @PostMapping("/backup-report")
    @Transactional
    public ResponseEntity<Void> backupReport(@RequestBody BackupReportDto body, HttpServletRequest request) {
        requireInstance(request);
        var client = fleetRepository.findClientByCode(body.clientCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Клиент не найден"));
        fleetRepository.recordBackupCheck(client.id(), body.success(),
                body.durationSec() != null ? body.durationSec() : 0, body.details());
        return ResponseEntity.accepted().build();
    }

    private Long requireInstance(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Отсутствует токен экземпляра");
        }
        return fleetRepository.findInstanceByHeartbeatToken(CpPasswordHasher.sha256(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Экземпляр не зарегистрирован или токен недействителен"));
    }

    public record HeartbeatDto(String appVersion, String schemaVersion, Map<String, Object> metrics) {}

    public record BackupReportDto(String clientCode, boolean success, Integer durationSec, String details) {}
}
