package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    public MdOrgUnitService(MdOrgUnitRepository orgUnitRepository,
                            MdScopeService scopeService,
                            AuditLogService auditLogService) {
        this.orgUnitRepository = orgUnitRepository;
        this.scopeService = scopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<MdOrgUnitRepository.OrgUnitRecord> listAll() {
        return orgUnitRepository.listAll();
    }

    @Transactional(readOnly = true)
    public MdOrgUnitRepository.OrgUnitRecord getById(Long id) {
        return orgUnitRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Узел оргструктуры не найден"));
    }

    @Transactional
    public MdOrgUnitRepository.OrgUnitRecord create(Long parentId, String code, String name, String kind, int orderNo) {
        if (parentId != null) {
            getById(parentId);
        } else if (orgUnitRepository.hasRoot()) {
            // Экземпляр принадлежит одному клиенту (ADR-0004), поэтому дерево одно.
            // Без этой проверки ограничение БД срабатывало бы конфликтом без объяснения.
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "Корень оргструктуры уже существует — укажите родительский узел");
        }
        var unit = orgUnitRepository.create(parentId, code, name,
                kind != null && !kind.isBlank() ? kind : "department", orderNo);

        auditLogService.logChange("md_org_units", String.valueOf(unit.id()), "I",
                List.of("code", "name", "kind", "parent_id"),
                null,
                Map.of("code", code, "name", name, "kind", unit.kind(),
                        "parent_id", parentId != null ? parentId : "null"));

        return unit;
    }

    @Transactional
    public void update(Long id, Long parentId, String name, String kind, String state, Integer orderNo) {
        var unit = getById(id);

        // I-ORG-1: перенос под собственного потомка отрезал бы ветку от корня — молча.
        if (parentId != null && orgUnitRepository.isDescendant(id, parentId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "Узел нельзя перенести под собственного потомка");
        }

        String newName = name != null ? name : unit.name();
        String newKind = kind != null ? kind : unit.kind();
        String newState = state != null ? state : unit.state();
        int newOrderNo = orderNo != null ? orderNo : unit.orderNo();

        orgUnitRepository.update(id, parentId, newName, newKind, newState, newOrderNo);

        // Перенос ветки и выключение узла меняют видимость данных у всех,
        // кто стоит внутри неё — скоуп обязан пересчитаться в этой же транзакции.
        scopeService.recalculateForUnitSubtree(id);

        auditLogService.logChange("md_org_units", String.valueOf(id), "U",
                List.of("parent_id", "name", "kind", "state", "order_no"),
                Map.of("name", unit.name(), "kind", unit.kind(), "state", unit.state(),
                        "parent_id", unit.parentId() != null ? unit.parentId() : "null"),
                Map.of("name", newName, "kind", newKind, "state", newState,
                        "parent_id", parentId != null ? parentId : "null"));
    }

    @Transactional
    public void delete(Long id) {
        var unit = getById(id);

        // I-ORG-2: у узла есть дети или сотрудники — удаление здесь означало бы
        // либо каскад по дереву, либо потерю привязок. И то и другое молча.
        if (orgUnitRepository.hasChildren(id)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "У узла есть подчинённые узлы — сначала перенесите или удалите их");
        }
        if (orgUnitRepository.isAssignedToUsers(id)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "К узлу привязаны сотрудники — сначала снимите привязку");
        }

        orgUnitRepository.delete(id);

        auditLogService.logChange("md_org_units", String.valueOf(id), "D",
                List.of("code", "name"),
                Map.of("code", unit.code(), "name", unit.name()),
                null);
    }
}
