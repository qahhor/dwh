package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.repository.MdCustomModuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class MdCustomModuleService {

    private static final Logger log = LoggerFactory.getLogger(MdCustomModuleService.class);

    private final MdCustomModuleRepository repository;
    private final AuditLogService auditLogService;

    public MdCustomModuleService(MdCustomModuleRepository repository,
                                 AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    public List<MdCustomModuleRepository.CustomModuleRecord> listAll() {
        return repository.findAll();
    }

    public List<MdCustomModuleRepository.CustomModuleRecord> listApprovedActive() {
        return repository.findApprovedActive();
    }

    public MdCustomModuleRepository.CustomModuleRecord getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Модуль не найден: " + id));
    }

    @Transactional
    public MdCustomModuleRepository.CustomModuleRecord createModule(
            String code, String name, String version, String description, String category,
            String icon, String routePath, String entrypointUrl, String permissionsJson,
            String settingsSchemaJson, Long createdBy) {

        String safeCode = code != null ? code.toLowerCase().trim() : ("mod_" + UUID.randomUUID().toString().substring(0, 8));

        if (repository.findByCode(safeCode).isPresent()) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Модуль с кодом '" + safeCode + "' уже существует");
        }

        var created = repository.create(
                safeCode, name, version != null ? version : "1.0.0", description,
                category != null ? category : "custom", icon != null ? icon : "extension",
                routePath != null ? routePath : ("/custom/" + safeCode),
                entrypointUrl, permissionsJson, settingsSchemaJson, createdBy
        );
        auditLogService.logChange(
                "md_custom_modules",
                String.valueOf(created.id()),
                "I",
                List.of("code", "name", "version", "status"),
                Map.of(),
                auditSnapshot(created)
        );
        return created;
    }

    @Transactional
    public MdCustomModuleRepository.CustomModuleRecord submitForApproval(Long id) {
        var module = getById(id);

        if ("APPROVED".equals(module.status())) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Модуль уже одобрен и активен");
        }

        String ticketId = "TICKET-MOD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        repository.updateStatus(id, "PENDING_APPROVAL", null, ticketId);

        log.info("Custom module {} submitted for Control Plane approval (ticketId={})", module.code(), ticketId);
        var updated = getById(id);
        auditLogService.logChange(
                "md_custom_modules",
                String.valueOf(id),
                "U",
                List.of("status", "cp_ticket_id"),
                auditSnapshot(module),
                auditSnapshot(updated)
        );
        return updated;
    }

    @Transactional
    public void deleteModule(Long id) {
        var module = getById(id);
        repository.delete(id);
        auditLogService.logChange(
                "md_custom_modules",
                String.valueOf(id),
                "D",
                List.of("code", "name", "version", "status"),
                auditSnapshot(module),
                Map.of()
        );
    }

    private Map<String, Object> auditSnapshot(MdCustomModuleRepository.CustomModuleRecord module) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", module.code());
        snapshot.put("name", module.name());
        snapshot.put("version", module.version());
        snapshot.put("status", module.status());
        if (module.cpTicketId() != null) {
            snapshot.put("cp_ticket_id", module.cpTicketId());
        }
        return Map.copyOf(snapshot);
    }
}
