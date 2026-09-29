package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.OrgUnitView;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Оргструктура экземпляра (ADR-0013). Дерево, на которое опирается скоуп данных.
 *
 * Инварианты, которые держит приложение, а не база:
 * I-ORG-1 узел нельзя перенести под собственного потомка (цикл отрезал бы ветку от корня);
 * I-ORG-2 узел с детьми или с назначенными сотрудниками не удаляется молча.
 */
@Service
public class MdOrgUnitService {

    private final MdOrgUnitRepository orgUnitRepository;
    private final MdScopeService scopeService;
    private final AuditLogService auditLogService;

    public MdOrgUnitService(
            MdOrgUnitRepository orgUnitRepository, MdScopeService scopeService, AuditLogService auditLogService) {
        this.orgUnitRepository = orgUnitRepository;
        this.scopeService = scopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<OrgUnitView> listAll() {
        return orgUnitRepository.listAll().stream().map(MdOrgUnitService::view).toList();
    }

    @Transactional(readOnly = true)
    public OrgUnitView getById(Long id) {
        return view(requireUnit(id));
    }

    private MdOrgUnitRepository.OrgUnitRecord requireUnit(Long id) {
        requirePositiveId(id);
        return orgUnitRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.org_unit_not_found"));
    }

    @Transactional
    public OrgUnitView create(Long parentId, String code, String name, String kind, int orderNo) {
        scopeService.acquireMutationLock();
        code = requiredText(code, "error.md.org_unit_code_required");
        name = requiredText(name, "error.md.org_unit_name_required");
        kind = kind == null ? "department" : requiredText(kind, "error.md.org_unit_kind_required");
        if (parentId != null) {
            requireUnit(parentId);
        } else if (orgUnitRepository.hasRoot()) {
            // Экземпляр принадлежит одному клиенту (ADR-0004), поэтому дерево одно.
            // Без этой проверки ограничение БД срабатывало бы конфликтом без объяснения.
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_root_exists");
        }
        if (orgUnitRepository.existsByCode(code)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_code_exists");
        }
        var unit = orgUnitRepository.create(parentId, code, name, kind, orderNo);
        scopeService.recalculateForUnitSubtree(unit.id());

        auditLogService.logChange(
                "md_org_units",
                String.valueOf(unit.id()),
                "I",
                List.of("code", "name", "kind", "parent_id"),
                null,
                Map.of(
                        "code",
                        code,
                        "name",
                        name,
                        "kind",
                        unit.kind(),
                        "parent_id",
                        parentId != null ? parentId : "null"));

        return view(unit);
    }

    private static OrgUnitView view(MdOrgUnitRepository.OrgUnitRecord unit) {
        return new OrgUnitView(
                unit.id(),
                unit.parentId(),
                unit.code(),
                unit.name(),
                unit.kind(),
                unit.state(),
                unit.orderNo(),
                unit.createdAt(),
                unit.modifiedAt(),
                unit.revision());
    }

    @Transactional
    public long update(
            Long id, Long parentId, String name, String kind, String state, Integer orderNo, long expectedRevision) {
        return update(id, true, parentId, name, kind, state, orderNo, expectedRevision);
    }

    @Transactional
    public long update(
            Long id,
            boolean parentIdPresent,
            Long parentId,
            String name,
            String kind,
            String state,
            Integer orderNo,
            long expectedRevision) {
        scopeService.acquireMutationLock();
        var unit = requireUnit(id);
        Long finalParentId = parentIdPresent ? parentId : unit.parentId();

        String newName = name != null ? requiredText(name, "error.md.org_unit_name_required") : unit.name();
        String newKind = kind != null ? requiredText(kind, "error.md.org_unit_kind_required") : unit.kind();
        String newState = state != null ? state : unit.state();
        if (!"A".equals(newState) && !"P".equals(newState)) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "error.md.org_unit_state_invalid");
        }
        int newOrderNo = orderNo != null ? orderNo : unit.orderNo();

        if (finalParentId == null && unit.parentId() != null) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_parent_required");
        }
        if (finalParentId != null) {
            requireUnit(finalParentId);
            if (unit.parentId() == null) {
                throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_root_move_forbidden");
            }
        }

        // I-ORG-1: перенос под собственного потомка отрезал бы ветку от корня — молча.
        if (finalParentId != null && orgUnitRepository.isDescendant(id, finalParentId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_move_under_descendant");
        }

        var affectedUsers = new TreeSet<>(scopeService.getUserIdsAffectedByUnit(id));
        long revision =
                orgUnitRepository.update(id, finalParentId, newName, newKind, newState, newOrderNo, expectedRevision);
        affectedUsers.addAll(scopeService.getUserIdsAffectedByUnit(id));
        for (Long userId : affectedUsers) {
            scopeService.recalculateFor(userId);
        }

        auditLogService.logChange(
                "md_org_units",
                String.valueOf(id),
                "U",
                List.of("parent_id", "name", "kind", "state", "order_no"),
                Map.of(
                        "name",
                        unit.name(),
                        "kind",
                        unit.kind(),
                        "state",
                        unit.state(),
                        "parent_id",
                        unit.parentId() != null ? unit.parentId() : "null"),
                Map.of(
                        "name",
                        newName,
                        "kind",
                        newKind,
                        "state",
                        newState,
                        "parent_id",
                        finalParentId != null ? finalParentId : "null"));
        return revision;
    }

    @Transactional
    public void delete(Long id) {
        scopeService.acquireMutationLock();
        var unit = requireUnit(id);

        // I-ORG-2: у узла есть дети или сотрудники — удаление здесь означало бы
        // либо каскад по дереву, либо потерю привязок. И то и другое молча.
        if (orgUnitRepository.hasChildren(id)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_has_children");
        }
        if (orgUnitRepository.isAssignedToUsers(id)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.org_unit_has_users");
        }

        var affectedUsers = scopeService.getUserIdsAffectedByUnit(id);
        orgUnitRepository.delete(id);
        for (Long userId : affectedUsers) {
            scopeService.recalculateFor(userId);
        }

        auditLogService.logChange(
                "md_org_units",
                String.valueOf(id),
                "D",
                List.of("code", "name"),
                Map.of("code", unit.code(), "name", unit.name()),
                null);
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "error.md.org_unit_id_invalid");
        }
    }

    private static String requiredText(String value, String emptyKey) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, emptyKey);
        }
        return normalized;
    }
}
