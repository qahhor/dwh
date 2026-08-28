package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Назначение ролей и персональных прав пользователям (FR-PERM-4, FR-PERM-5, FR-PERM-10).
 *
 * Ключевое правило: любое изменение прав в ТОЙ ЖЕ транзакции пересчитывает
 * эффективные права и инкрементирует permissions_version (инвариант I-P2) —
 * иначе кэш в приложении не узнает об изменении и отзыв права не сработает.
 */
@Service
public class MdAssignmentService {

    private final MdUserRepository userRepository;
    private final MdRoleRepository roleRepository;
    private final MdPermissionRepository permissionRepository;
    private final MdPermissionService permissionService;

    public MdAssignmentService(MdUserRepository userRepository,
                               MdRoleRepository roleRepository,
                               MdPermissionRepository permissionRepository,
                               MdPermissionService permissionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<Long> getUserRoleIds(Long userId) {
        requireUser(userId);
        return roleRepository.getUserRoleIds(userId);
    }

    /** Полная замена набора ролей пользователя (семантика PUT из ТЗ-04 разд. 4.4). */
    @Transactional
    public long assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
        List<Long> requested = roleIds != null ? roleIds : List.of();

        for (Long roleId : requested) {
            roleRepository.findById(roleId).orElseThrow(() ->
                    ApiException.notFound(ErrorCode.ROLE_NOT_FOUND, "Роль не найдена: " + roleId));
        }
        guardLastAdmin(userId, requested);

        roleRepository.assignRolesToUser(userId, requested);
        permissionService.recalculateEffectivePermissions(userId);
        return permissionService.getPermissionVersion(userId);
    }

    /** Полная замена персональных прав поверх ролей (FR-PERM-5). */
    @Transactional
    public long replacePersonalPermissions(Long userId, List<MdRoleRepository.PermissionPair> permissions) {
        requireUser(userId);
        List<MdRoleRepository.PermissionPair> requested = permissions != null ? permissions : List.of();

        // Права выдаются только на существующие пары каталога (FR-PERM-1):
        // иначе в системе появятся «мёртвые» права, которые ничего не открывают.
        var catalog = permissionService.getFormCatalog().stream()
                .map(f -> f.formCode() + "." + f.action())
                .toList();
        for (var p : requested) {
            if (!catalog.contains(p.formCode() + "." + p.action())) {
                throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                        "Неизвестная пара форма/действие: " + p.formCode() + "." + p.action());
            }
        }

        permissionRepository.replaceUserPermissions(userId, requested);
        permissionService.recalculateEffectivePermissions(userId);
        return permissionService.getPermissionVersion(userId);
    }

    @Transactional(readOnly = true)
    public List<MdPermissionRepository.EffectivePermissionItem> getEffectivePermissions(Long userId) {
        requireUser(userId);
        return permissionRepository.getEffectivePermissionsWithSource(userId);
    }

    /**
     * Нельзя снять роль admin с последнего администратора: система осталась бы
     * без единого пользователя, способного управлять доступом (сценарий F-04).
     */
    private void guardLastAdmin(Long userId, List<Long> newRoleIds) {
        var adminRole = roleRepository.findByPcode(MdPref.ROLE_ADMIN).orElse(null);
        if (adminRole == null) {
            return;
        }
        boolean hadAdmin = roleRepository.getUserRoleIds(userId).contains(adminRole.id());
        boolean keepsAdmin = newRoleIds.contains(adminRole.id());
        if (hadAdmin && !keepsAdmin && userRepository.countUsersWithRole(adminRole.id()) <= 1) {
            throw ApiException.conflict(ErrorCode.LAST_ADMIN,
                    "Нельзя снять роль администратора с последнего администратора системы");
        }
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId).orElseThrow(() ->
                ApiException.notFound(ErrorCode.USER_NOT_FOUND, "Пользователь не найден"));
    }
}
