package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/iam/users")
public class MdUserController {

    private final MdUserService userService;

    public MdUserController(MdUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<KeysetPage<MdUserRepository.UserRecord>> listUsers(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "state", required = false) String state) {

        return ResponseEntity.ok(userService.listUsers(limit, cursor, search, state));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<MdUserRepository.UserRecord> getUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_USERS, action = "create")
    public ResponseEntity<MdUserRepository.UserRecord> createUser(@Valid @RequestBody CreateUserDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();

        var user = userService.createUser(
                body.name(),
                body.login(),
                body.email(),
                body.phone(),
                body.password(),
                body.managerId(),
                body.language(),
                body.timezone(),
                body.avatarFileId(),
                body.attributes(),
                body.is2faEnabled(),
                body.roleIds(),
                currentUserId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    public ResponseEntity<Void> updateUser(@PathVariable("id") Long id, @RequestBody UpdateUserDto body) {
        Long currentUserId = SecurityContext.getCurrentUserId();

        userService.updateUser(
                id,
                body.name(),
                body.phone(),
                body.managerId(),
                body.language(),
                body.timezone(),
                body.avatarFileId(),
                body.attributes(),
                body.is2faEnabled(),
                currentUserId
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/block")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    public ResponseEntity<Void> blockUser(@PathVariable("id") Long id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userService.setUserState(id, MdPref.STATE_PASSIVE, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unblock")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "unblock")
    public ResponseEntity<Void> unblockUser(@PathVariable("id") Long id) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userService.setUserState(id, MdPref.STATE_ACTIVE, currentUserId);
        return ResponseEntity.noContent().build();
    }

    public record CreateUserDto(
            @NotBlank String name,
            @NotBlank @Size(min = 3, max = 50) String login,
            @NotBlank @Email String email,
            String phone,
            @NotBlank @Size(min = 8) String password,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            List<Long> roleIds
    ) {}

    public record UpdateUserDto(
            String name,
            String phone,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            Boolean is2faEnabled
    ) {}
}
