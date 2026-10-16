package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Create-only declarations and delivery packages for the developer CLI (ADR-0037). */
public final class MdSimpleFormDtos {
    private MdSimpleFormDtos() {}

    @Schema(name = "SimpleFormLabels", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Labels(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 80)
            String ru,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 80)
            String uz,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 80)
            String en) {}

    @Schema(name = "SimpleFormModule", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Module(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9]{1,11}$")
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title) {}

    @Schema(name = "SimpleFormEntity", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Entity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title) {}

    @Schema(name = "SimpleFormField", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Field(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9]{0,19}$")
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

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(minimum = "1", maximum = "4000") @Nullable
            Integer maxLength,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(minimum = "0", maximum = "6") @Nullable
            Integer scale) {}

    @Schema(name = "SimpleFormDeclaration", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
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

    @Schema(name = "SimpleFormDeliveryPackage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DeliveryPackage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1")
            int formatVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "simple-form-package")
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Declaration declaration,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
            String checksum) {}
}
