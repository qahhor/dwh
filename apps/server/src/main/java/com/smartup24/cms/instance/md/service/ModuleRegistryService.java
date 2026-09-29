package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.repository.ModuleRegistryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModuleRegistryService {

    private final ModuleRegistryRepository moduleRepository;
    private final AuditLogService auditLogService;

    public ModuleRegistryService(ModuleRegistryRepository moduleRepository, AuditLogService auditLogService) {
        this.moduleRepository = moduleRepository;
        this.auditLogService = auditLogService;
    }

    public record InstalledModuleView(
            String code,
            String name,
            String description,
            String version,
            String icon,
            String route,
            boolean isSystem,
            String status,
            int sortOrder,
            Map<String, Object> attributes,
            Instant createdAt,
            Instant modifiedAt) {
        public static InstalledModuleView from(ModuleRegistryRepository.InstalledModuleRecord r) {
            return new InstalledModuleView(
                    r.code(),
                    r.name(),
                    r.description(),
                    r.version(),
                    r.icon(),
                    r.route(),
                    r.isSystem(),
                    r.status(),
                    r.sortOrder(),
                    r.attributes(),
                    r.createdAt(),
                    r.modifiedAt());
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "allModules", key = "'all'")
    public List<InstalledModuleView> getAllModules() {
        return moduleRepository.findAll().stream()
                .map(InstalledModuleView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "activeModules", key = "'active'")
    public List<InstalledModuleView> getActiveModules() {
        return moduleRepository.findActive().stream()
                .map(InstalledModuleView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<InstalledModuleView> getModule(String code) {
        return moduleRepository.findByCode(code).map(InstalledModuleView::from);
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
            return InstalledModuleView.from(existing);
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
        return InstalledModuleView.from(updated);
    }

    @Transactional
    @Caching(
            evict = {
                @CacheEvict(value = "activeModules", allEntries = true),
                @CacheEvict(value = "allModules", allEntries = true),
                @CacheEvict(value = "moduleActive", allEntries = true)
            })
    public InstalledModuleView registerModule(
            String code,
            String name,
            String description,
            String version,
            String icon,
            String route,
            boolean isSystem,
            int sortOrder,
            Map<String, Object> attributes) {
        var record = new ModuleRegistryRepository.InstalledModuleRecord(
                code.toLowerCase().trim(),
                name,
                description,
                version != null ? version : "1.0.0",
                icon != null ? icon : "box",
                route,
                isSystem,
                "ACTIVE",
                sortOrder,
                attributes != null ? attributes : Map.of(),
                Instant.now(),
                Instant.now());
        moduleRepository.upsertModule(record);

        auditLogService.logChange(
                "md_installed_modules",
                record.code(),
                "I",
                List.of("code", "name", "version", "status"),
                null,
                Map.of("code", record.code(), "name", record.name(), "version", record.version(), "status", "ACTIVE"));

        return InstalledModuleView.from(record);
    }
}
