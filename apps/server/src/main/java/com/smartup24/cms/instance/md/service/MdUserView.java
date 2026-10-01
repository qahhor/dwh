package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A safe user projection for API responses.
 * The repository record (UserRecord) is NEVER exposed:
 * it contains password_hash (a leak found by a live check on 2026-08-28).
 */
public record MdUserView(
        Long id,
        String name,
        String login,
        String email,
        String phone,
        String state,
        Long managerId,
        String language,
        String timezone,
        UUID avatarFileId,
        Map<String, Object> attributes,
        boolean is2faEnabled,
        boolean forcePasswordChange,
        List<Long> roleIds,
        Instant createdAt,
        Instant modifiedAt,
        long revision)
        implements Revisioned {
    public static MdUserView from(MdUserRepository.UserRecord u, List<Long> roleIds) {
        return new MdUserView(
                u.id(),
                u.name(),
                u.login(),
                u.email(),
                u.phone(),
                u.state(),
                u.managerId(),
                u.language(),
                u.timezone(),
                u.avatarFileId(),
                u.attributes(),
                u.is2faEnabled(),
                u.forcePasswordChange(),
                roleIds != null ? roleIds : List.of(),
                u.createdAt(),
                u.modifiedAt(),
                u.revision());
    }

    public static MdUserView from(MdUserRepository.UserRecord u) {
        return from(u, List.of());
    }

    public static MdUserView from(MdUserService.AuthUser u) {
        return from(u, List.of());
    }

    public static MdUserView from(MdUserService.AuthUser u, List<Long> roleIds) {
        return new MdUserView(
                u.id(),
                u.name(),
                u.login(),
                u.email(),
                u.phone(),
                u.state(),
                u.managerId(),
                u.language(),
                u.timezone(),
                u.avatarFileId(),
                u.attributes(),
                u.is2faEnabled(),
                u.forcePasswordChange(),
                roleIds != null ? roleIds : List.of(),
                u.createdAt(),
                u.modifiedAt(),
                u.revision());
    }
}
