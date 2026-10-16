package com.smartup24.cms.instance.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Presentation declarations: field identities and data rules remain in the entity definition. */
public final class FormLayoutDtos {
    private FormLayoutDtos() {}

    public record LocalizedLabels(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String ru,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String uz,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String en) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormLayoutField(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String fieldId,

            @Nullable LocalizedLabels labels) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormLayoutSection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id,

            @Nullable LocalizedLabels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<FormLayoutField> fields) {
        public FormLayoutSection {
            fields = List.copyOf(fields);
        }
    }

    public record FormLayoutDeclaration(
            @Schema(minimum = "1", maximum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            int formatVersion,

            @Schema(
                    allowableValues = {"form-layout"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String kind,

            @Schema(
                    allowableValues = {"example.products"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String entityCode,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<FormLayoutSection> sections) {
        public FormLayoutDeclaration {
            sections = List.copyOf(sections);
        }
    }

    public record FormLayoutPreview(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormLayoutDeclaration declaration,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormMeta meta) {}
}
