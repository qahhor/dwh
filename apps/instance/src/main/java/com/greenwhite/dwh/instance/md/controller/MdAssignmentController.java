package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.service.MdAssignmentService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> getUserRoles(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(Map.of("roleIds", assignmentService.getUserRoleIds(userId)));
    }

    @PutMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<Map<String, Object>> assignRoles(@PathVariable("userId") Long userId,
                                                           @RequestBody AssignRolesDto body) {
        long version = assignmentService.assignRoles(userId, body.roleIds());
        return ResponseEntity.ok(Map.of("permissionsVersion", version));
    }

    @GetMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<Map<String, Object>> getPersonalPermissions(@PathVariable("userId") Long userId) {
        var items = assignmentService.getEffectivePermissions(userId).stream()
                .filter(i -> "personal".equals(i.source()))
                .map(i -> Map.of("form", i.formCode(), "action", i.action()))
                .toList();
        return ResponseEntity.ok(Map.of("grants", items));
    }

    @PutMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<Map<String, Object>> replacePersonalPermissions(
            @PathVariable("userId") Long userId,
            @RequestBody ReplacePermissionsDto body) {

        List<MdRoleRepository.PermissionPair> pairs = body.grants() == null ? List.of()
                : body.grants().stream()
                        .map(g -> new MdRoleRepository.PermissionPair(g.form(), g.action()))
                        .toList();
        long version = assignmentService.replacePersonalPermissions(userId, pairs);
        return ResponseEntity.ok(Map.of("permissionsVersion", version));
    }

    /** Экран «права глазами пользователя» (FR-PERM-10): что есть и откуда пришло. */
    @GetMapping("/effective-permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<Map<String, Object>> getEffectivePermissions(@PathVariable("userId") Long userId) {
        var items = assignmentService.getEffectivePermissions(userId).stream()
                .map(i -> Map.of("form", i.formCode(), "action", i.action(), "source", i.source()))
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    public record AssignRolesDto(List<Long> roleIds) {}

    public record ReplacePermissionsDto(List<GrantDto> grants) {}

    public record GrantDto(@NotBlank String form, @NotBlank String action) {}
}
