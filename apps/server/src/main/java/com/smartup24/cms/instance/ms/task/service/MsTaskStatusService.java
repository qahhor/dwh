package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTypeRepository;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выделенный сервис для управления динамическими статусами и типами задач (SRP).
 */
@Service
public class MsTaskStatusService {

    private final MsTaskStatusRepository statusRepository;
    private final MsTaskTypeRepository typeRepository;
    private final SearchChangePublisher searchChangePublisher;
    private final AuditLogService auditLogService;

    @Autowired
    public MsTaskStatusService(
            MsTaskStatusRepository statusRepository,
            MsTaskTypeRepository typeRepository,
            SearchChangePublisher searchChangePublisher,
            @Autowired(required = false) AuditLogService auditLogService) {
        this.statusRepository = statusRepository;
        this.typeRepository = typeRepository;
        this.searchChangePublisher = searchChangePublisher;
        this.auditLogService = auditLogService;
    }

    public MsTaskStatusService(
            MsTaskStatusRepository statusRepository,
            MsTaskTypeRepository typeRepository,
            SearchChangePublisher searchChangePublisher) {
        this(statusRepository, typeRepository, searchChangePublisher, null);
    }

    // =========================================================================
    // Dynamic Statuses
    // =========================================================================
    @Transactional
    @Cacheable(value = "taskStatuses", key = "'all'")
    public List<MsTaskStatusRepository.StatusRecord> listStatuses() {
        statusRepository.initDefaultStatusesIfEmpty();
        return statusRepository.listStatuses();
    }

    @Transactional
    @CacheEvict(value = "taskStatuses", allEntries = true)
    public MsTaskStatusRepository.StatusRecord createStatus(
            String pcode, String name, String color, int orderNo, boolean isTerminal) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.status_name_required");
        }
        var record = statusRepository.create(pcode, name, color, orderNo, isTerminal);
        if (auditLogService != null) {
            auditLogService.logChange(
                    "ms_task_statuses",
                    String.valueOf(record.id()),
                    "I",
                    List.of("pcode", "name", "color", "order_no", "is_terminal"),
                    null,
                    Map.of("name", name, "order_no", orderNo, "is_terminal", isTerminal));
        }
        return record;
    }

    @Transactional
    @CacheEvict(value = "taskStatuses", allEntries = true)
    public void updateStatusRecord(Long id, String name, String color, Integer orderNo, Boolean isTerminal) {
        if (statusRepository.findById(id).isEmpty()) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.status_not_found");
        }
        statusRepository.update(id, name, color, orderNo, isTerminal);
        if (name != null) searchChangePublisher.statusChanged(id);
        if (auditLogService != null) {
            auditLogService.logChange(
                    "ms_task_statuses",
                    String.valueOf(id),
                    "U",
                    List.of("name", "color", "order_no", "is_terminal"),
                    null,
                    Map.of("name", name != null ? name : ""));
        }
    }

    @Transactional
    @CacheEvict(value = "taskStatuses", allEntries = true)
    public void deleteStatus(Long id) {
        var status = statusRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.status_not_found"));
        if (status.pcode() != null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.status_system_delete");
        }
        boolean deleted = statusRepository.delete(id);
        if (!deleted) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.status_in_use");
        }
        if (auditLogService != null) {
            auditLogService.logChange("ms_task_statuses", String.valueOf(id), "D", List.of("id"), null, null);
        }
    }

    @Transactional
    @CacheEvict(value = "taskStatuses", allEntries = true)
    public void reorderStatuses(List<Long> orderedIds) {
        statusRepository.reorder(orderedIds);
    }

    // =========================================================================
    // Dynamic Types
    // =========================================================================
    @Transactional
    @Cacheable(value = "taskTypes", key = "'all'")
    public List<MsTaskTypeRepository.TypeRecord> listTypes() {
        typeRepository.initDefaultTypesIfEmpty();
        return typeRepository.listTypes();
    }

    @Transactional
    @CacheEvict(value = "taskTypes", allEntries = true)
    public MsTaskTypeRepository.TypeRecord createType(
            String code, String name, String icon, String color, int orderNo) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.type_code_name_required");
        }
        String cleanCode = code.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (typeRepository.findByCode(cleanCode).isPresent()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.type_code_exists");
        }
        var record = typeRepository.create(cleanCode, name, icon, color, orderNo);
        if (auditLogService != null) {
            auditLogService.logChange(
                    "ms_task_types",
                    String.valueOf(record.id()),
                    "I",
                    List.of("code", "name", "icon", "color", "order_no"),
                    null,
                    Map.of("code", cleanCode, "name", name));
        }
        return record;
    }

    @Transactional
    @CacheEvict(value = "taskTypes", allEntries = true)
    public void updateType(Long id, String name, String icon, String color, Integer orderNo) {
        if (typeRepository.findById(id).isEmpty()) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.type_not_found");
        }
        typeRepository.update(id, name, icon, color, orderNo);
        if (auditLogService != null) {
            auditLogService.logChange(
                    "ms_task_types",
                    String.valueOf(id),
                    "U",
                    List.of("name", "icon", "color", "order_no"),
                    null,
                    Map.of("name", name != null ? name : ""));
        }
    }

    @Transactional
    @CacheEvict(value = "taskTypes", allEntries = true)
    public void deleteType(Long id) {
        var type = typeRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.task.type_not_found"));
        if (type.isSystem()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.task.type_system_delete");
        }
        typeRepository.delete(id);
        if (auditLogService != null) {
            auditLogService.logChange("ms_task_types", String.valueOf(id), "D", List.of("id"), null, null);
        }
    }

    @Transactional
    @CacheEvict(value = "taskTypes", allEntries = true)
    public void reorderTypes(List<Long> orderedIds) {
        typeRepository.reorder(orderedIds);
    }
}
