package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Wire format of {@code /api/v1/iam} roles and the form catalog. */
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
     * One pair of the permission catalog. Entities and the static form catalog name rights by dictionary keys
     * (ADR-0031): {@code formNameKey} and {@code actionNameKey} let the screen use the viewer's language. Unknown
     * names have null keys and keep the stored {@code formName}/{@code actionName} words.
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
