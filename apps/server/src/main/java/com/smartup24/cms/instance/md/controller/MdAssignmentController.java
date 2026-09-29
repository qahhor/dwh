package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.AssignRolesDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.EffectivePermissionsResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.GrantsResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.PermissionsVersionResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.ReplacePermissionsDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.RoleIdsResponse;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdAssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Назначение ролей и персональных прав (ТЗ-04 разд. 4.4, форма rbac.assignments).
 * Разделено с MdRoleController сознательно: там управление самими ролями
 * (rbac.roles), здесь — кому что выдано (rbac.assignments), и права на эти
 * операции разные по матрице ролей (разд. 4.4.1 ТЗ-01).
 */
@RestController
@RequestMapping("/api/v1/iam/users/{userId}")
public class MdAssignmentController {

    private final MdAssignmentService assignmentService;

    public MdAssignmentController(MdAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<RoleIdsResponse> getUserRoles(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new RoleIdsResponse(assignmentService.getUserRoleIds(userId)));
    }

    @PutMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<PermissionsVersionResponse> assignRoles(
            @PathVariable("userId") Long userId, @Valid @RequestBody AssignRolesDto body) {
        long version = assignmentService.assignRoles(userId, body.roleIds());
        return ResponseEntity.ok(new PermissionsVersionResponse(version));
    }

    @GetMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<GrantsResponse> getPersonalPermissions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new GrantsResponse(assignmentService.getPersonalGrants(userId)));
    }

    @PutMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<PermissionsVersionResponse> replacePersonalPermissions(
            @PathVariable("userId") Long userId, @Valid @RequestBody ReplacePermissionsDto body) {
        long version = assignmentService.replacePersonalPermissions(userId, body.grants());
        return ResponseEntity.ok(new PermissionsVersionResponse(version));
    }

    /** Экран «права глазами пользователя» (FR-PERM-10): что есть и откуда пришло. */
    @GetMapping("/effective-permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<EffectivePermissionsResponse> getEffectivePermissions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new EffectivePermissionsResponse(assignmentService.getEffectivePermissions(userId)));
    }
}
