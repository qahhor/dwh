package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.md.service.PasswordValidator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request bodies and list filters of {@code /api/v1/iam/users}. The answer is
 * {@link com.smartup24.cms.instance.md.service.MdUserView}, which the sign-in answer shares.
 */
public final class MdUserDtos {

    private MdUserDtos() {}

    public record CreateUserDto(
            @NotBlank String name,
            @NotBlank @Size(min = 3, max = 50) String login,
            @NotBlank @Email String email,
            String phone,

            @NotBlank @Size(min = PasswordValidator.MIN_PASSWORD_LENGTH, max = PasswordValidator.MAX_PASSWORD_LENGTH)
            String password,

            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            Boolean forcePasswordChange,
            List<Long> roleIds) {}

    public record UpdateUserDto(
            String name,
            String phone,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            Boolean is2faEnabled,
            List<Long> roleIds) {}

    /** The flat query filters the list took before the registry, kept for existing callers. */
    public record UserListFilters(String state, Long roleId, Long managerId, Boolean is2faEnabled) {}
}
