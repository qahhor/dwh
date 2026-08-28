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
        getRoleById(id);
        roleRepository.update(id, name, state, orderNo != null ? orderNo : 0);
    }

    @Transactional
    public void deleteRole(Long id) {
        getRoleById(id);
        roleRepository.delete(id);
    }

    @Transactional(readOnly = true)
    public Set<String> getRolePermissions(Long roleId) {
        return roleRepository.getRolePermissions(roleId);
    }

    @Transactional
    public void setRolePermissions(Long roleId, List<MdRoleRepository.PermissionPair> permissions) {
        getRoleById(roleId);
        roleRepository.replaceRolePermissions(roleId, permissions);
    }
}
