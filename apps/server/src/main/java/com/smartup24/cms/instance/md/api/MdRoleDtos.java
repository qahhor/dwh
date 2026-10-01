package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Wire format of {@code /api/v1/rbac} and {@code /api/v1/iam} roles and the form catalog. */
public final class MdRoleDtos {

    private MdRoleDtos() {}

    public record RoleView(
            Long id,
            String name,
            String pcode,
            String state,
            int orderNo,
            Instant createdAt,
            Instant modifiedAt,
            long revision)
            implements Revisioned {}

    public record CreateRoleDto(@NotBlank String name, int orderNo) {}

    public record UpdateRoleDto(String name, String state, Integer orderNo) {}

    /** One pair of a role's permission matrix. */
    public record RolePermission(
            @NotBlank String formCode, @NotBlank String action) {}

    /**
     * One pair of the permission catalog. A declared entity names its right by dictionary keys (ADR-0031, plan
     * 10/10, item 5.0): {@code formNameKey} and {@code actionNameKey} are then set and the screen shows them in the
     * viewer's language; otherwise they are null and {@code formName}/{@code actionName} are the stored words.
     */
    public record FormCatalogItem(
            String formCode,
            String module,
            String formName,
            @Nullable String formNameKey,
            String action,
            String actionName,
            @Nullable String actionNameKey,
            boolean isDeprecated) {}
}
