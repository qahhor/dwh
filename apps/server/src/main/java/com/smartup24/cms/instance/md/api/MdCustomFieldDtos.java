package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/** Wire format of {@code /api/v1/custom-fields}. */
public final class MdCustomFieldDtos {

    private MdCustomFieldDtos() {}

    /** {@code optionsJson} stays a JSON string: the settings screen parses it itself. */
    public record CustomFieldView(
            Long id,
            String entityType,
            String code,
            String name,
            String fieldType,
            boolean isRequired,
            String defaultValue,
            String optionsJson,
            int orderNo,
            Instant createdAt,
            long revision)
            implements Revisioned {}

    public record CreateCustomFieldDto(
            @NotBlank String entityType,
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String fieldType,
            boolean isRequired,
            String defaultValue,
            Object options,
            int orderNo) {}

    public record UpdateCustomFieldDto(
            String name, Boolean isRequired, String defaultValue, Object options, Integer orderNo) {}
}
