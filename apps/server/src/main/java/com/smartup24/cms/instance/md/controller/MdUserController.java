package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdUserDtos.CreateUserDto;
import com.smartup24.cms.instance.md.api.MdUserDtos.UpdateUserDto;
import com.smartup24.cms.instance.md.api.MdUserDtos.UserListFilters;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserListService;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/iam/users")
public class MdUserController {

    private final MdUserService userService;
    private final MdUserSecurityService userSecurityService;
    private final MdUserListService userListService;

    public MdUserController(
            MdUserService userService, MdUserSecurityService userSecurityService, MdUserListService userListService) {
        this.userService = userService;
        this.userSecurityService = userSecurityService;
        this.userListService = userListService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<KeysetPage<MdUserView>> listUsers(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "roleId", required = false) Long roleId,
            @RequestParam(name = "managerId", required = false) Long managerId,
            @RequestParam(name = "is2faEnabled", required = false) Boolean is2faEnabled) {

        // Registry list iam.users (ADR-0016); `search` and the flat filters are kept for existing callers.
        return ResponseEntity.ok(userListService.pageViews(
                SecurityContext.getCurrentUserId(),
                limit,
                cursor,
                filter,
                sort,
                query != null && !query.isBlank() ? query : search,
                new UserListFilters(state, roleId, managerId, is2faEnabled)));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<MdUserView> getUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userService.getUserView(id));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_USERS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<MdUserView> createUser(@Valid @RequestBody CreateUserDto body) {
        var user = userService.createUser(body, SecurityContext.getCurrentUserId());
        return Created.at("/api/v1/iam/users/{id}", user.id(), user);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateUser(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateUserDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        long expectedRevision = Revisions.required(ifMatch);

        long revision = userService.updateUser(
                id,
                body.name(),
                body.phone(),
                body.managerId(),
                body.language(),
                body.timezone(),
                body.avatarFileId(),
                body.attributes(),
                body.is2faEnabled(),
                body.roleIds(),
                currentUserId,
                expectedRevision);

        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @PostMapping("/{id}/block")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> blockUser(@PathVariable("id") Long id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userSecurityService.setUserState(id, MdPref.STATE_PASSIVE, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unblock")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> unblockUser(@PathVariable("id") Long id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userSecurityService.setUserState(id, MdPref.STATE_ACTIVE, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteUser(@PathVariable("id") Long id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userSecurityService.anonymizeUser(id, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
