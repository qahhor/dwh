package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService.InstalledModuleView;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "List modules", description = "Every registered module with its state.")
    @GetMapping
    @RequiresPermission(form = "md.modules", action = "view")
    public ResponseEntity<List<InstalledModuleView>> getAllModules() {
        return ResponseEntity.ok(moduleService.getAllModules());
    }

    @Operation(summary = "List active modules", description = "The modules that are switched on.")
    @GetMapping("/active")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<InstalledModuleView>> getActiveModules() {
        return ResponseEntity.ok(moduleService.getActiveModules());
    }

    @Operation(summary = "Get a module", description = "One registered module.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "md.modules", action = "view")
    public ResponseEntity<InstalledModuleView> getModule(@PathVariable String code) {
        return moduleService
                .getModule(code)
                .map(ResponseEntity::ok)
                .orElseThrow(() ->
                        ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.module_not_found", Map.of("code", code)));
    }

    /** Switches the module on or off (plan item 3.4): the body states the result, so a repeat changes nothing. */
    @Operation(
            summary = "Switch a module on or off",
            description = "Sets the state of a module; the body states the result, so a repeat changes nothing.")
    @PutMapping("/{code}/enabled")
    @RequiresPermission(form = "md.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> setEnabled(
            @PathVariable String code, @RequestBody ToggleStatusRequest body) {
        return ResponseEntity.ok(moduleService.toggleModuleStatus(code, body.enabled()));
    }

    /** Deprecated for PUT /{code}/enabled; answers until its sunset (ADR-0023). */
    @Operation(
            summary = "Toggle a module (deprecated)",
            description =
                    "Flips the state of a module. Deprecated for PUT /api/v1/modules/{code}/enabled; answers until its sunset.")
    @PostMapping("/{code}/toggle")
    @RequiresPermission(form = "md.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> toggleModule(
            @PathVariable String code,
            @RequestBody(required = false) ToggleStatusRequest body,
            @RequestParam(required = false) Boolean active) {
        boolean enabled = (body != null) ? body.enabled() : (active != null ? active : true);
        return ResponseEntity.ok(moduleService.toggleModuleStatus(code, enabled));
    }

    /**
     * Registers the module, or replaces its registration made from the revision named in {@code If-Match} (ADR-0024):
     * without it a module that exists already is 428, from an older revision 409. The answer carries the new revision.
     */
    @Operation(
            summary = "Register a module",
            description =
                    "Registers a module or replaces its registration; the same call twice leaves the same module.")
    @PutMapping("/{code}")
    @RequiresPermission(form = "md.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> putModule(
            @PathVariable String code,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody RegisterModuleRequest body) {
        return ResponseEntity.ok(moduleService.putModule(
                code,
                body.name(),
                body.description(),
                body.version(),
                body.icon(),
                body.route(),
                body.sortOrder(),
                body.attributes(),
                Revisions.optional(ifMatch)));
    }

    /**
     * Deprecated for PUT /{code}: it replaces an existing registration whatever its revision, as before, until its
     * sunset (ADR-0023); the replace still raises the revision, so a PUT made from the older one gets 409.
     */
    @Operation(
            summary = "Register a module (deprecated)",
            description =
                    "Registers a module, replacing an existing registration. Deprecated for PUT /api/v1/modules/{code}; answers until its sunset.")
    @PostMapping
    @RequiresPermission(form = "md.modules", action = "manage")
    public ResponseEntity<InstalledModuleView> registerModule(@RequestBody RegisterModuleRequest body) {
        return ResponseEntity.ok(moduleService.registerModule(
                body.code(),
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
