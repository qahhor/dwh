package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.AssignRolesDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.EffectivePermissionsResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.GrantsResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.PermissionsVersionResponse;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.ReplacePermissionsDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.RoleIdsResponse;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assignment of roles and personal permissions (form md.assignments).
 * Deliberately separate from MdRoleController: that one manages the roles themselves
 * (md.roles), this one manages who is granted what (md.assignments), and the role
 * matrix grants different permissions for these operations.
 */
@RestController
@RequestMapping("/api/v1/iam/users/{userId}")
public class MdAssignmentController {

    private final MdAssignmentService assignmentService;

    public MdAssignmentController(MdAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(summary = "Get the roles of a user", description = "The roles assigned to a user.")
    @GetMapping("/roles")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<RoleIdsResponse> getUserRoles(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new RoleIdsResponse(assignmentService.getUserRoleIds(userId)));
    }

    @Operation(
            summary = "Replace the roles of a user",
            description = "Replaces the whole set of roles assigned to a user.")
    @PutMapping("/roles")
    @AnswersRevision
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<PermissionsVersionResponse> assignRoles(
            @PathVariable("userId") Long userId,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignRolesDto body) {
        // The roles are part of the user: they are saved from the user's revision (plan 10/10, item 3.6).
        var saved = assignmentService.assignRoles(userId, body.roleIds(), Revisions.required(ifMatch));
        return ResponseEntity.ok().eTag(Revisions.etag(saved.revision())).body(saved);
    }

    @Operation(
            summary = "Get the personal permissions of a user",
            description = "The permissions granted to a user directly, beside their roles.")
    @GetMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<GrantsResponse> getPersonalPermissions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new GrantsResponse(assignmentService.getPersonalGrants(userId)));
    }

    @Operation(
            summary = "Replace the personal permissions of a user",
            description = "Replaces the whole set of permissions granted to a user directly.")
    @PutMapping("/permissions")
    @AnswersRevision
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "assign")
    public ResponseEntity<PermissionsVersionResponse> replacePersonalPermissions(
            @PathVariable("userId") Long userId,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReplacePermissionsDto body) {
        // The personal rights are part of the user, like its roles (plan 10/10, item 3.6).
        var saved = assignmentService.replacePersonalPermissions(userId, body.grants(), Revisions.required(ifMatch));
        return ResponseEntity.ok().eTag(Revisions.etag(saved.revision())).body(saved);
    }

    /** The "permissions as the user sees them" screen (FR-PERM-10): what the user has and where it came from. */
    @Operation(
            summary = "Get the effective permissions of a user",
            description =
                    "Every permission a user has, with where it comes from: a role or a personal grant (FR-PERM-10).")
    @GetMapping("/effective-permissions")
    @RequiresPermission(form = MdPref.FORM_ASSIGNMENTS, action = "view")
    public ResponseEntity<EffectivePermissionsResponse> getEffectivePermissions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(new EffectivePermissionsResponse(assignmentService.getEffectivePermissions(userId)));
    }
}
