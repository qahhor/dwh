package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpFleetRepository;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Реестр клиентов и дашборд флота (FR-CP-1, FR-CP-2, FR-CP-6). */
@RestController
@RequestMapping("/api/v1")
public class CpFleetController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CpFleetRepository fleetRepository;

    public CpFleetController(CpFleetRepository fleetRepository) {
        this.fleetRepository = fleetRepository;
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
    public ResponseEntity<Map<String, Object>> createClient(@RequestBody CreateClientDto body) {
        if (fleetRepository.findClientByCode(body.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Клиент с таким кодом уже существует");
        }
        Long id = fleetRepository.createClient(body.code(), body.name(),
                body.resourceProfile() != null ? body.resourceProfile() : "S");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "code", body.code()));
    }

    // ---------------------------------------------------------- экземпляры

    /**
     * Регистрация экземпляра. Возвращает heartbeat-токен — единственный раз,
     * как и любой токен в системе: в БД хранится только его hash.
     */
    @PostMapping("/instances")
    @CpRequiresRole({CpPref.ROLE_ADMIN})
    @Transactional
    public ResponseEntity<Map<String, Object>> registerInstance(@RequestBody RegisterInstanceDto body) {
        var client = fleetRepository.findClientByCode(body.clientCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Клиент не найден"));

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Long id = fleetRepository.createInstance(client.id(),
                body.environment() != null ? body.environment() : "production",
                body.url(), CpPasswordHasher.sha256(rawToken));

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "instanceId", id,
                "heartbeatToken", rawToken,
                "note", "Токен показывается один раз — сохраните его в конфигурации экземпляра"));
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
            @RequestBody UpdateStatusDto body) {
        fleetRepository.updateInstanceStatus(id, body.status());
        return ResponseEntity.ok(Map.of("instanceId", id, "status", body.status()));
    }

    public record CreateClientDto(@NotBlank String code, @NotBlank String name, String resourceProfile) {}

    public record RegisterInstanceDto(@NotBlank String clientCode, String environment, @NotBlank String url) {}

    public record UpdateStatusDto(@NotBlank String status) {}
}
