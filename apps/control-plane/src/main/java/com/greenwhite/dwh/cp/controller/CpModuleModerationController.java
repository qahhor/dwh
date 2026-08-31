package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpModuleModerationRepository;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import com.greenwhite.dwh.cp.security.CpSecurityContext;
import com.greenwhite.dwh.cp.service.CpModuleModerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CpModuleModerationController {

    private final CpModuleModerationService moderationService;

    public CpModuleModerationController(CpModuleModerationService moderationService) {
        this.moderationService = moderationService;
    }

    public record ModuleSubmissionDto(
            @NotNull Long instanceId,
            @NotBlank String clientCode,
            @NotBlank String moduleCode,
            @NotBlank String name,
            String version,
            String description,
            String category,
            String icon,
            String routePath,
            @NotBlank String entrypointUrl,
            String permissionsJson
    ) {}

    public record ModerationDecisionDto(
            String notes
    ) {}

    @GetMapping("/moderation/modules")
    @CpRequiresRole(CpPref.ROLE_ADMIN)
    public ResponseEntity<List<CpModuleModerationRepository.InstanceModuleRecord>> listModules() {
        return ResponseEntity.ok(moderationService.listAll());
    }

    @PostMapping("/instances/modules/submit")
    public ResponseEntity<CpModuleModerationRepository.InstanceModuleRecord> submitModule(
            @Valid @RequestBody ModuleSubmissionDto body) {
        var record = moderationService.submitModuleFromInstance(
                body.instanceId(), body.clientCode(), body.moduleCode(), body.name(),
                body.version(), body.description(), body.category(), body.icon(),
                body.routePath(), body.entrypointUrl(), body.permissionsJson()
        );
        return ResponseEntity.ok(record);
    }

    @PostMapping("/moderation/modules/{id}/approve")
    @CpRequiresRole(CpPref.ROLE_ADMIN)
    public ResponseEntity<CpModuleModerationRepository.InstanceModuleRecord> approveModule(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ModerationDecisionDto body) {
        var principal = CpSecurityContext.get();
        String reviewer = principal != null ? principal.login() : "cpadmin";
        String notes = body != null ? body.notes() : "Одобрено администратором Control Plane";
        return ResponseEntity.ok(moderationService.approveModule(id, notes, reviewer));
    }

    @PostMapping("/moderation/modules/{id}/reject")
    @CpRequiresRole(CpPref.ROLE_ADMIN)
    public ResponseEntity<CpModuleModerationRepository.InstanceModuleRecord> rejectModule(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ModerationDecisionDto body) {
        var principal = CpSecurityContext.get();
        String reviewer = principal != null ? principal.login() : "cpadmin";
        String notes = body != null ? body.notes() : "Отклонено службой безопасности Control Plane";
        return ResponseEntity.ok(moderationService.rejectModule(id, notes, reviewer));
    }
}
