package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.repository.ModuleRegistryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModuleRegistryService {

    private final ModuleRegistryRepository moduleRepository;
    private final AuditLogService auditLogService;
    private final ModuleCatalog modules;

    public ModuleRegistryService(
            ModuleRegistryRepository moduleRepository, AuditLogService auditLogService, ModuleCatalog modules) {
        this.moduleRepository = moduleRepository;
        this.auditLogService = auditLogService;
        this.modules = modules;
    }

    /**
     * A registered module; its version, the least platform API version it needs and its dependencies come from its
     * manifest (ADR-0033, 6.4) and are null or empty for a module registered through the API without code behind it.
     */
    public record InstalledModuleView(
            String code,
            String name,
            String description,
            @Nullable String version,
            @Nullable String minPlatform,
            List<ModuleDependencyView> dependencies,
            String icon,
            String route,
            boolean isSystem,
            String status,
            int sortOrder,
            Map<String, Object> attributes,
            Instant createdAt,
            Instant modifiedAt,
            long revision)
            implements Revisioned {
        public static InstalledModuleView from(
                ModuleRegistryRepository.InstalledModuleRecord r, @Nullable ModuleManifest manifest) {
            return new InstalledModuleView(
                    r.code(),
                    r.name(),
                    r.description(),
                    manifest == null ? null : manifest.version().toString(),
                    manifest == null ? null : manifest.minPlatform().toString(),
                    manifest == null
                            ? List.of()
                            : manifest.dependencies().stream()
                                    .map(dependency -> new ModuleDependencyView(
                                            dependency.code(),
                                            dependency.version().toString()))
                                    .toList(),
                    r.icon(),
                    r.route(),
                    r.isSystem(),
                    r.status(),
                    r.sortOrder(),
                    r.attributes(),
                    r.createdAt(),
                    r.modifiedAt(),
                    r.revision());
        }
    }

    /** A module another one needs and the least version of it, from the manifest (ADR-0033, 6.2). */
    public record ModuleDependencyView(String code, String version) {}

    private InstalledModuleView view(ModuleRegistryRepository.InstalledModuleRecord record) {
        return InstalledModuleView.from(record, modules.find(record.code()).orElse(null));
    }

    /**
     * Gives a module whose manifest is on the classpath a row in the registry, switched on and not a system one, when
     * it has none yet (ADR-0033, 6.4): a module outside the monorepo is installed by putting its jar on the classpath.
     * The row of a module that has one already stays as it is. Answers the codes registered now.
     */
    @Transactional
    @Caching(
            evict = {
                @CacheEvict(value = "activeModules", allEntries = true),
                @CacheEvict(value = "allModules", allEntries = true),
                @CacheEvict(value = "moduleActive", allEntries = true)
            })
    public List<String> registerManifests() {
        List<String> registered = new ArrayList<>();
        for (ModuleManifest manifest : modules.manifests()) {
            var record = registration(manifest.code(), manifest.name(), "", null, null, 100, Map.of());
            if (moduleRepository.insertModule(record).isPresent()) {
                auditRegistration(record);
                registered.add(manifest.code());
            }
        }
        return List.copyOf(registered);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "allModules", key = "'all'")
    public List<InstalledModuleView> getAllModules() {
        return moduleRepository.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "activeModules", key = "'active'")
    public List<InstalledModuleView> getActiveModules() {
        return moduleRepository.findActive().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<InstalledModuleView> getModule(String code) {
        return moduleRepository.findByCode(code).map(this::view);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "moduleActive", key = "#code != null ? #code.toLowerCase().trim() : ''")
    public boolean isModuleActive(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return moduleRepository
                .findByCode(code.toLowerCase().trim())
                .map(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                .orElse(false);
    }

    @Transactional
    @Caching(
            evict = {
                @CacheEvict(value = "activeModules", allEntries = true),
                @CacheEvict(value = "allModules", allEntries = true),
                @CacheEvict(value = "moduleActive", allEntries = true)
            })
    public InstalledModuleView toggleModuleStatus(String code, boolean enable) {
        var existing = moduleRepository
                .findByCode(code)
                .orElseThrow(() ->
                        ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.module_not_found", Map.of("code", code)));

        if (existing.isSystem()) {
            throw ApiException.badRequest(
                    ErrorCode.BAD_REQUEST, "error.md.system_module_disable_forbidden", Map.of("code", code));
        }

        String newStatus = enable ? "ACTIVE" : "DISABLED";
        if (existing.status().equals(newStatus)) {
            return view(existing);
        }

        moduleRepository.updateStatus(code, newStatus);

        auditLogService.logChange(
                "md_installed_modules",
                code,
                "U",
                List.of("status"),
                Map.of("status", existing.status()),
                Map.of("status", newStatus));

        var updated = moduleRepository
                .findByCode(code)
                .orElseThrow(() ->
                        ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.module_not_found", Map.of("code", code)));
        return view(updated);
    }

    /**
     * Registers a new module, or replaces the registration of an existing one made from {@code expectedRevision}
     * (plan 10/10, item 3.6, ADR-0024): a replace that names no revision is 428, one from an older revision 409, so of
     * two administrators replacing it from the same read the second does not undo the first. The status stays.
     */
    @Transactional
    @Caching(
            evict = {
                @CacheEvict(value = "activeModules", allEntries = true),
                @CacheEvict(value = "allModules", allEntries = true),
                @CacheEvict(value = "moduleActive", allEntries = true)
            })
    public InstalledModuleView putModule(
            String code,
            String name,
            String description,
            String icon,
            String route,
            int sortOrder,
            Map<String, Object> attributes,
            @Nullable Long expectedRevision) {
        var record = registration(code, name, description, icon, route, sortOrder, attributes);
        if (expectedRevision == null) {
            if (moduleRepository.insertModule(record).isEmpty()) {
                throw Revisions.missing();
            }
            auditRegistration(record);
            return find(record.code());
        }
        var before = moduleRepository.findByCode(record.code()).orElseThrow(() -> notFound(record.code()));
        if (moduleRepository.replaceModule(record, expectedRevision).isEmpty()) {
            throw Revisions.conflict();
        }
        auditLogService.logChange(
                "md_installed_modules",
                record.code(),
                "U",
                List.of("name", "route"),
                registrationFields(before),
                registrationFields(record));
        return find(record.code());
    }

    private static ModuleRegistryRepository.InstalledModuleRecord registration(
            String code,
            String name,
            String description,
            @Nullable String icon,
            @Nullable String route,
            int sortOrder,
            Map<String, Object> attributes) {
        // A module registered through the API is never a system one.
        return new ModuleRegistryRepository.InstalledModuleRecord(
                code.toLowerCase().trim(),
                name,
                description,
                icon != null ? icon : "box",
                route,
                false,
                "ACTIVE",
                sortOrder,
                attributes != null ? attributes : Map.of(),
                Instant.now(),
                Instant.now(),
                1L);
    }

    private void auditRegistration(ModuleRegistryRepository.InstalledModuleRecord record) {
        auditLogService.logChange(
                "md_installed_modules",
                record.code(),
                "I",
                List.of("code", "name", "status"),
                null,
                Map.of("code", record.code(), "name", record.name(), "status", "ACTIVE"));
    }

    private static Map<String, Object> registrationFields(ModuleRegistryRepository.InstalledModuleRecord record) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", record.name());
        fields.put("route", record.route() != null ? record.route() : "");
        return fields;
    }

    private InstalledModuleView find(String code) {
        return view(moduleRepository.findByCode(code).orElseThrow(() -> notFound(code)));
    }

    private static ApiException notFound(String code) {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.module_not_found", Map.of("code", code));
    }
}
