package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The layout of a live form of simple-form format 4 and later (ADR-0041): its tabs and sections. */
public final class MdSimpleFormLayoutDtos {
    private MdSimpleFormLayoutDtos() {}

    /** A tab of a live form's card and form, from format 4 on (ADR-0041): it shows the sections that name it. */
    @Schema(name = "SimpleFormTab", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Tab(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels) {}

    /** A titled group of the fields of a live form, from format 4 on (ADR-0041); on a tab when the form has tabs. */
    @Schema(name = "SimpleFormSection", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Section(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The key of the tab the section is on; present exactly when the form has tabs")
            @Nullable
            String tab,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 20,
                    arraySchema =
                            @Schema(
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    description = "The ids of the shown fields of the section, in their order"))
            List<String> fields) {}

    /**
     * The layout of a live form, from format 4 on (ADR-0041): sections, each shown field on exactly one of them, and
     * optionally 2 to 6 tabs; without it the form has one section of every field.
     */
    @Schema(name = "SimpleFormLayout", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Layout(
            @JsonInclude(JsonInclude.Include.NON_NULL) @ArraySchema(minItems = 2, maxItems = 6) @Nullable
            List<Tab> tabs,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 10,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Section> sections) {}
}
