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

    /** The menu item of a live form, from format 2 on (ADR-0041); without it the form takes the default item. */
    @Schema(name = "SimpleFormMenu", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Menu(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {
                        "box",
                        "description",
                        "assignment",
                        "event",
                        "storefront",
                        "local_shipping",
                        "inventory_2",
                        "people",
                        "support_agent",
                        "receipt_long"
                    })
            String icon,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"workspace", "administration"})
            String section,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "999")
            int order) {}

    @Schema(name = "SimpleFormEntity", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Entity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Menu menu) {
        /** An entity of format 1, which has no menu of its own. */
        public Entity(String name, Labels title) {
            this(name, title, null);
        }
    }

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
            Integer scale,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 2: the field is kept with its data but leaves the form, the list, the"
                            + " export and the history (ADR-0041); present only when true")
            @Nullable
            Boolean hidden,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 2: the fixed value a new record takes, written as the field reads it",
                    minLength = 1,
                    maxLength = 4000)
            @Nullable
            String defaultValue) {
        /** A field of format 1: shown, without a default. */
        public Field(
                String id,
                String type,
                Labels labels,
                boolean required,
                boolean listed,
                @Nullable Integer maxLength,
                @Nullable Integer scale) {
            this(id, type, labels, required, listed, maxLength, scale, null, null);
        }
    }

    @Schema(name = "SimpleFormDeclaration", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Declaration(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minimum = "1",
                    maximum = "2",
                    description = "1, or 2 for a live form with hidden fields, defaults or its own menu item")
            int formatVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "simple-form")
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Module module,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Entity entity,

            @ArraySchema(
                    maxItems = 30,
                    arraySchema =
                            @Schema(
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    description = "At most 20 fields that are not hidden"))
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
