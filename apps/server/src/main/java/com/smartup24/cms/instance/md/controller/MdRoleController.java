package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/rbac", "/api/v1/iam"})
public class MdRoleController {

    private final MdRoleService roleService;
    private final MdPermissionService permissionService;

    public MdRoleController(MdRoleService roleService, MdPermissionService permissionService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    @GetMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "view")
    public ResponseEntity<List<MdRoleRepository.RoleRecord>> listRoles() {
        return ResponseEntity.ok(roleService.listRoles());
    }

    @GetMapping("/roles/user-counts")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "view")
    public ResponseEntity<Map<Long, Integer>> getRoleUserCounts() {
        return ResponseEntity.ok(roleService.countUsersPerRole());
    }

    @PostMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "create")
    public ResponseEntity<MdRoleRepository.RoleRecord> createRole(@Valid @RequestBody CreateRoleDto body) {
        var role = roleService.createRole(body.name(), body.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    @PatchMapping("/roles/{id}")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "update")
    public ResponseEntity<Void> updateRole(@PathVariable("id") Long id, @RequestBody UpdateRoleDto body) {
        roleService.updateRole(id, body.name(), body.state(), body.orderNo());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/roles/{id}")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "delete")
    public ResponseEntity<Void> deleteRole(@PathVariable("id") Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles/{id}/permissions")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "view")
    public ResponseEntity<Set<String>> getRolePermissions(@PathVariable("id") Long id) {
        return ResponseEntity.ok(roleService.getRolePermissions(id));
    }

    @PutMapping("/roles/{id}/permissions")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "grant")
    public ResponseEntity<Void> setRolePermissions(
            @PathVariable("id") Long id, @RequestBody List<MdRoleRepository.PermissionPair> permissions) {

        roleService.setRolePermissions(id, permissions);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/forms")
    @RequiresPermission(form = MdPref.FORM_ROLES, action = "view")
    public ResponseEntity<List<MdPermissionRepository.FormTreeItem>> getFormCatalog() {
        return ResponseEntity.ok(permissionService.getFormCatalog());
    }

    public record CreateRoleDto(@NotBlank String name, int orderNo) {}

    public record UpdateRoleDto(String name, String state, Integer orderNo) {}
}
