package com.smartup24.cms.instance.md.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Wire format of {@code /api/v1/iam/users/{userId}/roles|permissions|effective-permissions}. */
public final class MdAssignmentDtos {

    private MdAssignmentDtos() {}

    public record AssignRolesDto(@NotNull List<@NotNull Long> roleIds) {}

    public record ReplacePermissionsDto(@NotNull List<@NotNull @Valid GrantDto> grants) {}

    /** One personal grant; the same pair in the request and in the answer. */
    public record GrantDto(@NotBlank String form, @NotBlank String action) {}

    public record RoleIdsResponse(List<Long> roleIds) {}

    /**
     * The answer to a change of roles or personal rights.
     *
     * @param permissionsVersion the version of the user's effective rights after the change
     * @param revision the user's revision after the change (plan 10/10, item 3.6), also sent as {@code ETag}
     */
    public record PermissionsVersionResponse(long permissionsVersion, long revision) {}

    public record GrantsResponse(List<GrantDto> grants) {}

    /** A permission the user holds and where it comes from: {@code personal} or {@code role:<name>}. */
    public record EffectivePermission(String form, String action, String source) {}

    public record EffectivePermissionsResponse(List<EffectivePermission> items) {}
}
