package com.smartup24.cms.instance.md.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Wire format of {@code /api/v1/iam/users/{userId}/roles|permissions|effective-permissions}. */
public final class MdAssignmentDtos {

    private MdAssignmentDtos() {}

    public record AssignRolesDto(List<Long> roleIds) {}

    public record ReplacePermissionsDto(List<GrantDto> grants) {}

    /** One personal grant; the same pair in the request and in the answer. */
    public record GrantDto(@NotBlank String form, @NotBlank String action) {}

    public record RoleIdsResponse(List<Long> roleIds) {}

    public record PermissionsVersionResponse(long permissionsVersion) {}

    public record GrantsResponse(List<GrantDto> grants) {}

    /** A permission the user holds and where it comes from: {@code personal} or {@code role:<name>}. */
    public record EffectivePermission(String form, String action, String source) {}

    public record EffectivePermissionsResponse(List<EffectivePermission> items) {}
}
