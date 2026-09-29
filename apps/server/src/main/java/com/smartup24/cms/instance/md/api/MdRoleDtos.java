package com.smartup24.cms.instance.md.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/** Wire format of {@code /api/v1/rbac} and {@code /api/v1/iam} roles and the form catalog. */
public final class MdRoleDtos {

    private MdRoleDtos() {}

    public record RoleView(
            Long id, String name, String pcode, String state, int orderNo, Instant createdAt, Instant modifiedAt) {}

    public record CreateRoleDto(@NotBlank String name, int orderNo) {}

    public record UpdateRoleDto(String name, String state, Integer orderNo) {}

    /** One pair of a role's permission matrix. */
    public record RolePermission(String formCode, String action) {}

    public record FormCatalogItem(
            String formCode, String module, String formName, String action, String actionName, boolean isDeprecated) {}
}
