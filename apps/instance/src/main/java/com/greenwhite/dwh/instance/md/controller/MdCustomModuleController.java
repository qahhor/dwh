package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdCustomModuleRepository;
import com.greenwhite.dwh.instance.md.service.MdCustomModuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/modules")
public class MdCustomModuleController {

    private final MdCustomModuleService moduleService;

    public MdCustomModuleController(MdCustomModuleService moduleService) {
        this.moduleService = moduleService;
    }

    public record CreateModuleDto(
            @NotBlank String code,
            @NotBlank String name,
            String version,
            String description,
            String category,
            String icon,
            String routePath,
            @NotBlank String entrypointUrl,
            String permissionsJson,
            String settingsSchemaJson
    ) {}

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<List<MdCustomModuleRepository.CustomModuleRecord>> listModules() {
        return ResponseEntity.ok(moduleService.listAll());
    }

    @GetMapping("/active")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<List<MdCustomModuleRepository.CustomModuleRecord>> listActiveModules() {
        return ResponseEntity.ok(moduleService.listApprovedActive());
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<MdCustomModuleRepository.CustomModuleRecord> getModule(@PathVariable("id") Long id) {
        return ResponseEntity.ok(moduleService.getById(id));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<MdCustomModuleRepository.CustomModuleRecord> createModule(@Valid @RequestBody CreateModuleDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        var module = moduleService.createModule(
                body.code(), body.name(), body.version(), body.description(),
                body.category(), body.icon(), body.routePath(), body.entrypointUrl(),
                body.permissionsJson(), body.settingsSchemaJson(), currentUserId
        );
        return ResponseEntity.ok(module);
    }

    @PostMapping("/{id}/submit")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<MdCustomModuleRepository.CustomModuleRecord> submitModule(@PathVariable("id") Long id) {
        return ResponseEntity.ok(moduleService.submitForApproval(id));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<Void> deleteModule(@PathVariable("id") Long id) {
        moduleService.deleteModule(id);
        return ResponseEntity.noContent().build();
    }
}
