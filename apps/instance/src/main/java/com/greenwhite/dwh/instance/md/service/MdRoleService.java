package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class MdRoleService {

    private final MdRoleRepository roleRepository;
    private final MdPermissionService permissionService;

    public MdRoleService(MdRoleRepository roleRepository, MdPermissionService permissionService) {
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<MdRoleRepository.RoleRecord> listRoles() {
        return roleRepository.listRoles();
    }

    @Transactional
    public MdRoleRepository.RoleRecord createRole(String name, int orderNo) {
        return roleRepository.create(name, null, "A", orderNo);
    }

    @Transactional(readOnly = true)
    public MdRoleRepository.RoleRecord getRoleById(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.ROLE_NOT_FOUND, "Роль не найдена"));
    }

    @Transactional
    public void updateRole(Long id, String name, String state, Integer orderNo) {
        var role = getRoleById(id);
        if (role.pcode() != null && "admin".equals(role.pcode()) && "P".equalsIgnoreCase(state)) {
            throw ApiException.forbidden(ErrorCode.SUPERADMIN_IMMUTABLE, "Роль администратора не может быть переведена в пассивный статус");
        }
        roleRepository.update(id, name, state != null ? state : role.state(), orderNo != null ? orderNo : role.orderNo());
        if (state != null && !state.equals(role.state())) {
            List<Long> userIds = roleRepository.getUserIdsByRole(id);
            for (Long uid : userIds) {
                permissionService.recalculateEffectivePermissions(uid);
            }
        }
    }

    @Transactional
    public void deleteRole(Long id) {
        var role = getRoleById(id);
        if (role.pcode() != null) {
            throw ApiException.forbidden(ErrorCode.SUPERADMIN_IMMUTABLE, "Системные роли не могут быть удалены");
        }
        List<Long> userIds = roleRepository.getUserIdsByRole(id);
        if (!userIds.isEmpty()) {
            throw ApiException.conflict(ErrorCode.ROLE_NOT_FOUND, "Роль назначена пользователям и не может быть удалена");
        }
        roleRepository.delete(id);
    }

    @Transactional(readOnly = true)
    public Set<String> getRolePermissions(Long roleId) {
        return roleRepository.getRolePermissions(roleId);
    }

    @Transactional
    public void setRolePermissions(Long roleId, List<MdRoleRepository.PermissionPair> permissions) {
        var role = getRoleById(roleId);
        roleRepository.replaceRolePermissions(roleId, permissions);
        List<Long> userIds = roleRepository.getUserIdsByRole(roleId);
        for (Long uid : userIds) {
            permissionService.recalculateEffectivePermissions(uid);
        }
    }
}

