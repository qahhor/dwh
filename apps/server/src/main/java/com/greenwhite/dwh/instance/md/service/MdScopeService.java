package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.ScopeFilter;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Скоуп данных: кто какие строки видит (ADR-0013).
 *
 * Модель доступа Этапа 1 отвечала только на вопрос «можно ли открыть форму».
 * Здесь появляется второй вопрос — «какие строки в ней твои», без которого
 * дашборды Этапа 3 показывать нельзя.
 *
 * Правило видимости принадлежит роли, позиция в дереве — пользователю.
 * Это разделение позволяет одной ролью «региональный менеджер» обслужить все
 * регионы: правило одно, а видят её носители разное, потому что стоят
 * в разных узлах.
 */
@Service
public class MdScopeService {

    public static final String RULE_ALL = "ALL";
    public static final String RULE_SUBTREE = "SUBTREE";
    public static final String RULE_UNITS = "UNITS";
    public static final String RULE_SELF = "SELF";

    private static final Set<String> VALID_RULES = Set.of(RULE_ALL, RULE_SUBTREE, RULE_UNITS, RULE_SELF);

    private final MdScopeRepository scopeRepository;
    private final MdOrgUnitRepository orgUnitRepository;
    private final MdPermissionService permissionService;
    private final AuditLogService auditLogService;

    public MdScopeService(MdScopeRepository scopeRepository,
                          MdOrgUnitRepository orgUnitRepository,
                          MdPermissionService permissionService,
                          AuditLogService auditLogService) {
        this.scopeRepository = scopeRepository;
        this.orgUnitRepository = orgUnitRepository;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------- правило у роли

    @Transactional
    public void setRoleRule(Long roleId, String rule) {
        String normalized = normalize(rule);
        String before = scopeRepository.getRoleRule(roleId);

        scopeRepository.setRoleRule(roleId, normalized);
        recalculateForRole(roleId);

        // Смена правила меняет видимость данных так же радикально, как выдача
        // права, поэтому пишется в аудит наравне с матрицей прав (FR-AUD-1).
        auditLogService.logChange("md_role_scope_rules", String.valueOf(roleId), "U",
                List.of("rule"),
                Map.of("rule", before),
                Map.of("rule", normalized));
    }

    @Transactional(readOnly = true)
    public String getRoleRule(Long roleId) {
        return scopeRepository.getRoleRule(roleId);
    }

    // -------------------------------------------------- позиция пользователя

    @Transactional
    public void assignUserOrgUnits(Long userId, List<Long> orgUnitIds) {
        List<Long> requested = orgUnitIds != null ? orgUnitIds : List.of();
        for (Long unitId : requested) {
            orgUnitRepository.findById(unitId).orElseThrow(() ->
                    ApiException.notFound(ErrorCode.NOT_FOUND, "Узел оргструктуры не найден: " + unitId));
        }

        Set<Long> before = scopeRepository.getUserOrgUnitIds(userId);
        scopeRepository.replaceUserOrgUnits(userId, requested);
        recalculateFor(userId);

        auditLogService.logChange("md_user_org_units", String.valueOf(userId), "U",
                List.of("org_units"),
                Map.of("org_units", List.copyOf(before)),
                Map.of("org_units", List.copyOf(scopeRepository.getUserOrgUnitIds(userId))));
    }

    /**
     * Пересчёт эффективного скоупа пользователя. Версия прав двигается вместе
     * со скоупом: изменение видимости данных обязано инвалидировать кэш
     * доступа так же, как изменение права, иначе отзыв не сработает (I-P2).
     */
    @Transactional
    public String recalculateFor(Long userId) {
        String rule = scopeRepository.recalculateEffectiveScope(userId);
        permissionService.recalculateEffectivePermissions(userId);
        return rule;
    }

    @Transactional
    public void recalculateForRole(Long roleId) {
        for (Long userId : scopeRepository.getUserIdsByRole(roleId)) {
            recalculateFor(userId);
        }
    }

    /** После изменения дерева пересчитываются все, кто стоит в узле или под ним. */
    @Transactional
    public void recalculateForUnitSubtree(Long orgUnitId) {
        for (Long userId : scopeRepository.getUserIdsAffectedByUnit(orgUnitId)) {
            recalculateFor(userId);
        }
    }

    @Transactional(readOnly = true)
    public UserScope getUserScope(Long userId) {
        return new UserScope(scopeRepository.getUserRule(userId), scopeRepository.getEffectiveScope(userId));
    }

    // ------------------------------------------------------- применение в SQL

    /**
     * Ограничение выборки для текущего пользователя.
     *
     * Предикат добавляется в запрос явно, а не подставляется автоматически:
     * молчаливая фильтрация — это когда разработчик не видит, что его запрос
     * урезан, и отлаживает пустой список часами. Полнота покрытия
     * проверяется тестом на каждую скоупируемую сущность.
     *
     * @param orgUnitColumn колонка привязки строки к узлу, например {@code md_users.org_unit_id}
     * @param ownerColumn   колонка владельца строки для правила SELF
     */
    @Transactional(readOnly = true)
    public ScopeFilter filterFor(Long userId, String orgUnitColumn, String ownerColumn) {
        if (userId == null) {
            return ScopeFilter.unrestricted();
        }
        return switch (scopeRepository.getUserRule(userId)) {
            case RULE_SUBTREE, RULE_UNITS -> ScopeFilter.byOrgUnit(orgUnitColumn, userId);
            case RULE_SELF -> ScopeFilter.byOwner(ownerColumn, userId);
            default -> ScopeFilter.unrestricted();
        };
    }

    private static String normalize(String rule) {
        String normalized = rule != null ? rule.trim().toUpperCase() : "";
        if (!VALID_RULES.contains(normalized)) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "Неизвестное правило видимости: " + rule + ". Допустимо: " + VALID_RULES);
        }
        return normalized;
    }

    public record UserScope(String rule, Set<Long> visibleOrgUnitIds) {}
}
