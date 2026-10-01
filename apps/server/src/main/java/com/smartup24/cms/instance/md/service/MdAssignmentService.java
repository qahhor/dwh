package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.EffectivePermission;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.GrantDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.PermissionsVersionResponse;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns roles and personal permissions to users (FR-PERM-4, FR-PERM-5, FR-PERM-10).
 *
 * The key rule: every permission change recalculates effective permissions and increments
 * permissions_version in the SAME transaction (an ADR-0006 invariant);
 * otherwise the application cache never learns of the change and revoking a permission fails.
 *
 * A second rule of equal weight: the permission change is audited in the same transaction
 * (FR-AUD-1). Granting access without a trace in the log is indistinguishable from a compromise.
 */
@Service
public class MdAssignmentService {

    private final MdUserRepository userRepository;
    private final MdRoleRepository roleRepository;
    private final MdPermissionRepository permissionRepository;
    private final MdPermissionService permissionService;
    private final MdScopeService scopeService;
    private final AuditLogService auditLogService;

    public MdAssignmentService(
            MdUserRepository userRepository,
            MdRoleRepository roleRepository,
            MdPermissionRepository permissionRepository,
            MdPermissionService permissionService,
            MdScopeService scopeService,
            AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.permissionService = permissionService;
        this.scopeService = scopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<Long> getUserRoleIds(Long userId) {
        requireUser(userId);
        return roleRepository.getUserRoleIds(userId);
    }

    /**
     * Replaces the roles of a user (PUT semantics), made from the user's revision {@code expectedRevision}: the roles
     * are part of the user, so the change raises its revision and a stale form cannot undo it (plan 10/10, item 3.6).
     */
    @Transactional
    public PermissionsVersionResponse assignRoles(Long userId, List<Long> roleIds, long expectedRevision) {
        scopeService.acquireMutationLock();
        requireUser(userId);
        List<Long> requested = roleIds != null ? roleIds : List.of();

        for (Long roleId : requested) {
            ApiException.requirePresent(roleRepository.findById(roleId), () -> roleNotFound(roleId));
        }

        Set<Long> before = new TreeSet<>(roleRepository.getUserRoleIds(userId));
        guardLastAdmin(userId, requested);

        long revision = userRepository.nextRevision(userId, expectedRevision);
        roleRepository.assignRolesToUser(userId, requested);
        scopeService.recalculateFor(userId);

        Set<Long> after = new TreeSet<>(requested);
        var names = roleNames();
        auditLogService.logChange(
                "md_user_roles",
                String.valueOf(userId),
                "U",
                List.of("roles"),
                Map.of("roles", named(before, names)),
                Map.of(
                        "roles",
                        named(after, names),
                        "granted",
                        named(diff(after, before), names),
                        "revoked",
                        named(diff(before, after), names)));

        return new PermissionsVersionResponse(permissionService.getPermissionVersion(userId), revision);
    }

    /** Replaces the personal rights of a user on top of its roles (FR-PERM-5), made from the user's revision. */
    @Transactional
    public PermissionsVersionResponse replacePersonalPermissions(
            Long userId, List<GrantDto> grants, long expectedRevision) {
        requireUser(userId);
        List<MdRoleRepository.PermissionPair> requested = grants == null
                ? List.of()
                : grants.stream()
                        .map(g ->
                                new MdRoleRepository.PermissionPair(PermissionAreas.currentForm(g.form()), g.action()))
                        .toList();

        // Permissions are granted only on live catalog pairs (FR-PERM-1): an obsolete
        // pair opens nothing, and a permission granted on it is indistinguishable from
        // an access misconfiguration.
        var grantable = permissionService.getGrantablePairs();
        for (var p : requested) {
            if (!grantable.contains(p.formCode() + "." + p.action())) {
                throw ApiException.badRequest(
                        ErrorCode.VALIDATION_FAILED,
                        "error.md.permission_not_grantable",
                        Map.of("permission", p.formCode() + "." + p.action()));
            }
        }

        Set<String> before = new TreeSet<>(permissionRepository.getUserPersonalPermissions(userId));

        long revision = userRepository.nextRevision(userId, expectedRevision);
        permissionRepository.replaceUserPermissions(userId, requested);
        permissionService.recalculateEffectivePermissions(userId);

        Set<String> after = new TreeSet<>(permissionRepository.getUserPersonalPermissions(userId));
        auditLogService.logChange(
                "md_user_permissions",
                String.valueOf(userId),
                "U",
                List.of("permissions"),
                Map.of("permissions", List.copyOf(before)),
                Map.of(
                        "permissions",
                        List.copyOf(after),
                        "granted",
                        List.copyOf(diff(after, before)),
                        "revoked",
                        List.copyOf(diff(before, after))));

        return new PermissionsVersionResponse(permissionService.getPermissionVersion(userId), revision);
    }

    @Transactional(readOnly = true)
    public List<EffectivePermission> getEffectivePermissions(Long userId) {
        requireUser(userId);
        return permissionRepository.getEffectivePermissionsWithSource(userId).stream()
                .map(i -> new EffectivePermission(i.formCode(), i.action(), i.source()))
                .toList();
    }

    /** Only what was granted to the user personally, on top of the roles. */
    @Transactional(readOnly = true)
    public List<GrantDto> getPersonalGrants(Long userId) {
        requireUser(userId);
        return permissionRepository.getEffectivePermissionsWithSource(userId).stream()
                .filter(i -> "personal".equals(i.source()))
                .map(i -> new GrantDto(i.formCode(), i.action()))
                .toList();
    }

    /**
     * The admin role cannot be removed from the last administrator: the system would be left
     * without a single user able to manage access.
     */
    private void guardLastAdmin(Long userId, List<Long> newRoleIds) {
        var adminRole = roleRepository.findByPcode(MdPref.ROLE_ADMIN).orElse(null);
        if (adminRole == null) {
            return;
        }
        boolean hadAdmin = roleRepository.getUserRoleIds(userId).contains(adminRole.id());
        boolean keepsAdmin = newRoleIds.contains(adminRole.id());
        if (hadAdmin && !keepsAdmin && userRepository.countUsersWithRole(adminRole.id()) <= 1) {
            throw ApiException.conflict(ErrorCode.LAST_ADMIN, "error.md.last_admin_role");
        }
    }

    private static ApiException roleNotFound(Long roleId) {
        return ApiException.notFound(ErrorCode.ROLE_NOT_FOUND, "error.md.role_not_found_id", Map.of("id", roleId));
    }

    private void requireUser(Long userId) {
        ApiException.requirePresent(userRepository.findById(userId), () -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    /** Maps ids to names in one query. In the audit log a role name is readable and an id is not. */
    private Map<Long, String> roleNames() {
        return roleRepository.listRoles().stream()
                .collect(Collectors.toMap(
                        MdRoleRepository.RoleRecord::id, MdRoleRepository.RoleRecord::name, (a, b) -> a));
    }

    private static List<String> named(Set<Long> ids, Map<Long, String> names) {
        return ids.stream().map(id -> names.getOrDefault(id, "id:" + id)).toList();
    }

    /** What is in from and not in to. */
    private static <T extends Comparable<T>> Set<T> diff(Set<T> from, Set<T> to) {
        return from.stream().filter(x -> !to.contains(x)).collect(Collectors.toCollection(TreeSet::new));
    }
}
