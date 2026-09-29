package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService.InstalledModuleView;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/modules")
public class ModuleRegistryController {

    private final ModuleRegistryService moduleService;

    public ModuleRegistryController(ModuleRegistryService moduleService) {
        this.moduleService = moduleService;
    }

    public record ToggleStatusRequest(boolean enabled) {}

    public record RegisterModuleRequest(
            String code,
            String name,
            String description,
            String version,
            String icon,
            String route,
            int sortOrder,
            Map<String, Object> attributes) {}

    @GetMapping
    @RequiresPermission(form = "platform.modules", action = "view")
    public ResponseEntity<List<InstalledModuleView>> getAllModules() {
        return ResponseEntity.ok(moduleService.getAllModules());
    }

    @GetMapping("/active")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<InstalledModuleView>> getActiveModules() {
        return ResponseEntity.ok(moduleService.getActiveModules());
    }

    @GetMapping("/{code}")
    @RequiresPermission(form = "platform.modules", action = "view")
    public ResponseEntity<InstalledModuleView> getModule(@PathVariable String code) {
        return moduleService
                .getModule(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Switches the module on or off (plan item 3.4): the body states the result, so a repeat changes nothing. */
    @PutMapping("/{code}/enabled")
    @RequiresPermission(form = "platform.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> setEnabled(
            @PathVariable String code, @RequestBody ToggleStatusRequest body) {
        return ResponseEntity.ok(moduleService.toggleModuleStatus(code, body.enabled()));
    }

    /** Deprecated for PUT /{code}/enabled; answers until its sunset (ADR-0023). */
    @PostMapping("/{code}/toggle")
    @RequiresPermission(form = "platform.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> toggleModule(
            @PathVariable String code,
            @RequestBody(required = false) ToggleStatusRequest body,
            @RequestParam(required = false) Boolean active) {
        boolean enabled = (body != null) ? body.enabled() : (active != null ? active : true);
        return ResponseEntity.ok(moduleService.toggleModuleStatus(code, enabled));
    }

    /** Registers the module or replaces its registration: the same call twice leaves the same module. */
    @PutMapping("/{code}")
    @RequiresPermission(form = "platform.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> putModule(
            @PathVariable String code, @RequestBody RegisterModuleRequest body) {
        return register(code, body);
    }

    /** Deprecated for PUT /{code} (it always replaced an existing registration); answers until its sunset. */
    @PostMapping
    @RequiresPermission(form = "platform.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> registerModule(@RequestBody RegisterModuleRequest body) {
        return register(body.code(), body);
    }

    private ResponseEntity<InstalledModuleView> register(String code, RegisterModuleRequest body) {
        return ResponseEntity.ok(moduleService.registerModule(
                code,
                body.name(),
                body.description(),
                body.version(),
                body.icon(),
                body.route(),
                false,
                body.sortOrder(),
                body.attributes()));
    }
}
