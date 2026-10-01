package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdRoleDtos.RolePermission;
import com.smartup24.cms.instance.md.api.MdRoleDtos.RoleView;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles and their permission matrix (FR-PERM-2, FR-PERM-3).
 *
 * Every mutation leaves an audit trace: changing the permission matrix is the most
 * sensitive action in the system, and "who granted what to whom" must be
 * recoverable from the log without resorting to backups (FR-AUD-1).
 */
@Service
public class MdRoleService {

    private final MdRoleRepository roleRepository;
    private final MdPermissionService permissionService;
    private final AuditLogService auditLogService;
    private final MdScopeRepository scopeRepository;

    public MdRoleService(
            MdRoleRepository roleRepository,
            MdPermissionService permissionService,
            AuditLogService auditLogService,
            MdScopeRepository scopeRepository) {
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
        this.scopeRepository = scopeRepository;
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roleRepository.listRoles().stream().map(MdRoleService::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> countUsersPerRole() {
        return roleRepository.countUsersPerRole();
    }

    @Transactional
    public RoleView createRole(String name, int orderNo) {
        scopeRepository.lockScopeMutation();
        var role = roleRepository.create(name, null, "A", orderNo);

        // ADR-0013: the visibility rule is created at once and explicitly. A role without a rule
        // row would behave as ALL by default, which widens access by default,
        // and such things must be visible to the administrator in the list.
        scopeRepository.setRoleRule(role.id(), MdScopeService.RULE_ALL);

        auditLogService.logChange(
                "md_roles",
                String.valueOf(role.id()),
                "I",
                List.of("name", "state", "order_no"),
                null,
                Map.of("id", role.id(), "name", name, "state", "A", "order_no", orderNo));

        return view(role);
    }

    private static RoleView view(MdRoleRepository.RoleRecord role) {
        return new RoleView(
                role.id(),
                role.name(),
                role.pcode(),
                role.state(),
                role.orderNo(),
                role.createdAt(),
                role.modifiedAt(),
                role.revision());
    }

    @Transactional(readOnly = true)
    public MdRoleRepository.RoleRecord getRoleById(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.ROLE_NOT_FOUND));
    }

    @Transactional
    public long updateRole(Long id, String name, String state, Integer orderNo, long expectedRevision) {
        scopeRepository.lockScopeMutation();
        var role = getRoleById(id);
        if (role.pcode() != null && "admin".equals(role.pcode()) && "P".equalsIgnoreCase(state)) {
            throw ApiException.forbidden(ErrorCode.SUPERADMIN_IMMUTABLE, "error.md.admin_role_passive_forbidden");
        }
        // Partial update semantics: a field not sent is not changed.
        // A null name used to reach the database and fail on not null, and a missing
        // order_no silently reset the role's position in the list.
        String newName = name != null ? name : role.name();
        String newState = state != null ? state : role.state();
        int newOrderNo = orderNo != null ? orderNo : role.orderNo();
        long revision = roleRepository.update(id, newName, newState, newOrderNo, expectedRevision);
        if (state != null && !state.equals(role.state())) {
            List<Long> userIds = scopeRepository.getUserIdsByRole(id);
            for (Long uid : userIds) {
                scopeRepository.recalculateEffectiveScope(uid);
                permissionService.recalculateEffectivePermissions(uid);
            }
        }

        auditLogService.logChange(
                "md_roles",
                String.valueOf(id),
                "U",
                List.of("name", "state", "order_no"),
                Map.of("name", role.name(), "state", role.state(), "order_no", role.orderNo()),
                Map.of("name", newName, "state", newState, "order_no", newOrderNo));
        return revision;
    }

    @Transactional
    public void deleteRole(Long id) {
        scopeRepository.lockScopeMutation();
        var role = getRoleById(id);
        if (role.pcode() != null) {
            throw ApiException.forbidden(ErrorCode.SUPERADMIN_IMMUTABLE, "error.md.system_role_delete_forbidden");
        }
        List<Long> userIds = roleRepository.getUserIdsByRole(id);
        if (!userIds.isEmpty()) {
            throw ApiException.conflict(ErrorCode.ROLE_NOT_FOUND, "error.md.role_in_use");
        }
        roleRepository.delete(id);

        auditLogService.logChange(
                "md_roles",
                String.valueOf(id),
                "D",
                List.of("name", "state"),
                Map.of("name", role.name(), "state", role.state()),
                null);
    }

    @Transactional(readOnly = true)
    public Set<String> getRolePermissions(Long roleId) {
        return roleRepository.getRolePermissions(roleId);
    }

    @Transactional
    public long setRolePermissions(Long roleId, List<RolePermission> requested, long expectedRevision) {
        var role = getRoleById(roleId);
        List<MdRoleRepository.PermissionPair> permissions = requested == null
                ? null
                : requested.stream()
                        .map(p -> new MdRoleRepository.PermissionPair(
                                PermissionAreas.currentForm(p.formCode()), p.action()))
                        .toList();

        // The role matrix used not to be checked at all: any pair could be written to
        // md_role_permissions, and it got into the effective permissions (FR-PERM-1).
        var grantable = permissionService.getGrantablePairs();
        for (var p : permissions != null ? permissions : List.<MdRoleRepository.PermissionPair>of()) {
            if (!grantable.contains(p.formCode() + "." + p.action())) {
                throw ApiException.badRequest(
                        ErrorCode.VALIDATION_FAILED,
                        "error.md.permission_not_grantable",
                        Map.of("permission", p.formCode() + "." + p.action()));
            }
        }

        // The "before" snapshot is needed exactly here: after replace the old set cannot be recovered.
        Set<String> before = new TreeSet<>(roleRepository.getRolePermissions(roleId));

        long revision = roleRepository.nextRevision(roleId, expectedRevision);
        roleRepository.replaceRolePermissions(roleId, permissions);
        List<Long> userIds = roleRepository.getUserIdsByRole(roleId);
        for (Long uid : userIds) {
            permissionService.recalculateEffectivePermissions(uid);
        }

        Set<String> after = new TreeSet<>(roleRepository.getRolePermissions(roleId));
        auditLogService.logChange(
                "md_role_permissions",
                String.valueOf(roleId),
                "U",
                List.of("permissions"),
                Map.of("role", role.name(), "permissions", List.copyOf(before)),
                Map.of(
                        "role",
                        role.name(),
                        "permissions",
                        List.copyOf(after),
                        "granted",
                        diff(after, before),
                        "revoked",
                        diff(before, after),
                        "affected_users",
                        userIds.size()));
        return revision;
    }

    /** What is in {@code from} and not in {@code to}: a readable diff for the audit screen. */
    private static List<String> diff(Set<String> from, Set<String> to) {
        return from.stream().filter(p -> !to.contains(p)).toList();
    }
}
