package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.RoleRule;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.UserAssignments;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data scope: who sees which rows (ADR-0013).
 *
 * The original access model answered only "may this form be opened".
 * Here a second question appears, "which rows in it are yours", without which
 * analytics dashboards cannot be shown.
 *
 * The visibility rule belongs to the role, the position in the tree to the user.
 * This split lets one "regional manager" role serve all
 * regions: the rule is the same, but its holders see different rows because they sit
 * in different units.
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

    public MdScopeService(
            MdScopeRepository scopeRepository,
            MdOrgUnitRepository orgUnitRepository,
            MdPermissionService permissionService,
            AuditLogService auditLogService) {
        this.scopeRepository = scopeRepository;
        this.orgUnitRepository = orgUnitRepository;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    // ---------------------------------------------------------- role's rule

    /** Acquire before source rows or per-user materializations are changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireMutationLock() {
        scopeRepository.lockScopeMutation();
    }

    /**
     * Sets the scope rule of a role, made from the role's revision {@code expectedRevision}, and answers the role's new
     * revision: the rule is part of the role, so the change raises it and a stale screen cannot undo it (plan 10/10,
     * item 3.6).
     */
    @Transactional
    public long setRoleRule(Long roleId, String rule, long expectedRevision) {
        acquireMutationLock();
        requireRole(roleId);
        String normalized = normalize(rule);
        String before = scopeRepository.getRoleRule(roleId);

        long revision = scopeRepository.nextRoleRevision(roleId, expectedRevision);
        scopeRepository.setRoleRule(roleId, normalized);
        recalculateForRole(roleId);

        // Changing the rule changes data visibility as radically as granting
        // a permission, so it is audited just like the permission matrix (FR-AUD-1).
        auditLogService.logChange(
                "md_role_scope_rules",
                String.valueOf(roleId),
                "U",
                List.of("rule"),
                Map.of("rule", before),
                Map.of("rule", normalized));
        return revision;
    }

    @Transactional(readOnly = true)
    public String getRoleRule(Long roleId) {
        return scopeRepository.getRoleRule(roleId);
    }

    @Transactional(readOnly = true)
    public RoleRule getRoleScopeRule(Long roleId) {
        requireRole(roleId);
        long revision = scopeRepository.roleRevision(roleId);
        return new RoleRule(roleId, scopeRepository.getRoleRule(roleId), revision);
    }

    // -------------------------------------------------------- user position

    /**
     * Replaces the org units of a user, made from the user's revision {@code expectedRevision}, and answers the user's
     * new revision: the units are part of the user, so the change raises it (plan 10/10, item 3.6).
     */
    @Transactional
    public long assignUserOrgUnits(Long userId, List<Long> orgUnitIds, long expectedRevision) {
        acquireMutationLock();
        requireUser(userId);
        if (orgUnitIds == null) {
            throw validation("error.md.scope_units_required");
        }
        for (Long unitId : orgUnitIds) {
            requirePositiveId(unitId, "error.md.scope_unit_id_invalid");
        }
        List<Long> requested = List.copyOf(new TreeSet<>(orgUnitIds));
        for (Long unitId : requested) {
            ApiException.requirePresent(orgUnitRepository.findById(unitId), () -> unitNotFound(unitId));
        }

        Set<Long> before = scopeRepository.getUserOrgUnitIds(userId);
        long revision = scopeRepository.nextUserRevision(userId, expectedRevision);
        scopeRepository.replaceUserOrgUnits(userId, requested);
        recalculateFor(userId);

        auditLogService.logChange(
                "md_user_org_units",
                String.valueOf(userId),
                "U",
                List.of("org_units"),
                Map.of("org_units", List.copyOf(before)),
                Map.of("org_units", List.copyOf(scopeRepository.getUserOrgUnitIds(userId))));
        return revision;
    }

    /**
     * Recalculates the user's effective scope. The permissions version moves together
     * with the scope: a change of data visibility must invalidate the access cache
     * just like a permission change, otherwise revocation fails (an ADR-0006 invariant).
     */
    @Transactional
    public String recalculateFor(Long userId) {
        acquireMutationLock();
        String rule = scopeRepository.recalculateEffectiveScope(userId);
        permissionService.recalculateEffectivePermissions(userId);
        return rule;
    }

    @Transactional
    public void recalculateForRole(Long roleId) {
        acquireMutationLock();
        for (Long userId : scopeRepository.getUserIdsByRole(roleId)) {
            recalculateFor(userId);
        }
    }

    /** Users on either side of a tree mutation must be captured while holding the mutation lock. */
    @Transactional(readOnly = true)
    public List<Long> getUserIdsAffectedByUnit(Long orgUnitId) {
        return scopeRepository.getUserIdsAffectedByUnit(orgUnitId);
    }

    /** Refresh users assigned inside the branch or on its ancestors. */
    @Transactional
    public void recalculateForUnitSubtree(Long orgUnitId) {
        acquireMutationLock();
        for (Long userId : scopeRepository.getUserIdsAffectedByUnit(orgUnitId)) {
            recalculateFor(userId);
        }
    }

    @Transactional(readOnly = true)
    public UserScope getUserScope(Long userId) {
        requireUser(userId);
        return new UserScope(scopeRepository.getUserRule(userId), scopeRepository.getEffectiveScope(userId));
    }

    @Transactional(readOnly = true)
    public UserAssignments getUserAssignments(Long userId) {
        requireUser(userId);
        long revision = scopeRepository.userRevision(userId);
        return new UserAssignments(
                userId,
                scopeRepository.getUserOrgUnitIds(userId).stream().sorted().toList(),
                scopeRepository.findUserOrgUnit(userId).orElse(null),
                revision);
    }

    // ------------------------------------------------------- applying in SQL

    /**
     * The row restriction for the current user.
     *
     * The predicate is added to the query explicitly, not injected automatically:
     * silent filtering is when a developer does not see that the query is
     * cut down and spends hours debugging an empty list. Full coverage
     * is checked by a test for every scoped entity.
     *
     * @param orgUnitColumn the column linking a row to a unit, for example {@code md_users.org_unit_id}
     * @param ownerColumn   the row owner column for the SELF rule
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

    /** Row visibility for queries whose task table alias is {@code t}. */
    @Transactional(readOnly = true)
    public ScopeFilter filterForTasks(Long userId) {
        if (userId == null) {
            return ScopeFilter.unrestricted();
        }
        return switch (scopeRepository.getUserRule(userId)) {
            case RULE_SUBTREE, RULE_UNITS -> ScopeFilter.taskByParticipantOrgUnit(userId);
            case RULE_SELF -> ScopeFilter.taskSelf(userId);
            default -> ScopeFilter.unrestricted();
        };
    }

    /** Row visibility for queries whose file table alias is {@code f}. */
    @Transactional(readOnly = true)
    public ScopeFilter filterForFiles(Long userId) {
        if (userId == null) {
            return ScopeFilter.unrestricted();
        }
        return switch (scopeRepository.getUserRule(userId)) {
            case RULE_SUBTREE, RULE_UNITS -> ScopeFilter.fileByOwnerOrTaskOrgUnit(userId);
            case RULE_SELF -> ScopeFilter.fileSelf(userId);
            default -> ScopeFilter.unrestricted();
        };
    }

    /** Validates assignees before a scoped actor can add them to a task. */
    @Transactional(readOnly = true)
    public boolean canAccessUser(Long viewerId, Long targetUserId) {
        if (viewerId == null || targetUserId == null) {
            return viewerId == null;
        }
        return switch (scopeRepository.getUserRule(viewerId)) {
            case RULE_SELF -> viewerId.equals(targetUserId) && scopeRepository.userExists(targetUserId);
            case RULE_SUBTREE, RULE_UNITS -> scopeRepository.isUserInEffectiveScope(viewerId, targetUserId);
            default -> scopeRepository.userExists(targetUserId);
        };
    }

    private static String normalize(String rule) {
        String normalized = rule != null ? rule.trim().toUpperCase() : "";
        if (!VALID_RULES.contains(normalized)) {
            throw ApiException.badRequest(
                    ErrorCode.VALIDATION_FAILED,
                    "error.md.scope_rule_unknown",
                    Map.of("rule", String.valueOf(rule), "allowed", String.join(", ", new TreeSet<>(VALID_RULES))));
        }
        return normalized;
    }

    private void requireUser(Long userId) {
        requirePositiveId(userId, "error.md.user_id_invalid");
        if (!scopeRepository.userExists(userId)) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.user_not_found_id", Map.of("id", userId));
        }
    }

    private void requireRole(Long roleId) {
        requirePositiveId(roleId, "error.md.role_id_invalid");
        if (!scopeRepository.roleExists(roleId)) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.role_not_found_id", Map.of("id", roleId));
        }
    }

    private static ApiException unitNotFound(Long unitId) {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.org_unit_not_found_id", Map.of("id", unitId));
    }

    private static void requirePositiveId(Long id, String invalidKey) {
        if (id == null || id <= 0) {
            throw validation(invalidKey);
        }
    }

    private static ApiException validation(String messageKey) {
        return ApiException.badRequest(ErrorCode.VALIDATION_FAILED, messageKey);
    }

    public record UserScope(String rule, Set<Long> visibleOrgUnitIds) {}
}
