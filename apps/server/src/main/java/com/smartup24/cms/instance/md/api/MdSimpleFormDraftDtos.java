package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Structurally safe local authoring input; incomplete values are never a deployable declaration (ADR-0037). */
public final class MdSimpleFormDraftDtos {
    private MdSimpleFormDraftDtos() {}

    @Schema(name = "SimpleFormDraftLabels", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Labels(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String ru,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String uz,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String en) {}

    @Schema(name = "SimpleFormDraftModule", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Module(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title) {}

    @Schema(name = "SimpleFormDraftEntity", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Entity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title) {}

    @Schema(name = "SimpleFormDraftField", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Field(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String id,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {
                        "text",
                        "textarea",
                        "number",
                        "date",
                        "datetime",
                        "time",
                        "bool",
                        "email",
                        "phone",
                        "url"
                    })
            String type,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean required,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean listed,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Double maxLength,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Double scale) {}

    @Schema(name = "SimpleFormDraftDeclaration", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Declaration(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1")
            int formatVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "simple-form")
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Module module,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Entity entity,

            @ArraySchema(maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Field> fields) {}
}
