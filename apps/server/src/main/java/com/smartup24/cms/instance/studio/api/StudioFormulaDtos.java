package com.smartup24.cms.instance.studio.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The check of one formula while the editor types it (ADR-0042): the fields of the form it may read, the formula and
 * where it stands; the answer is its type or the reason and the position of its first problem. Nothing is stored.
 */
public final class StudioFormulaDtos {
    private StudioFormulaDtos() {}

    /** A field id of a simple form. */
    public static final String FIELD_ID = "^[a-z][a-z0-9]{0,19}$";

    /**
     * Where a formula stands: a field's computed value, default or condition, a rule, or a transition's condition of a
     * document (ADR-0043).
     */
    public static final String KIND = "^(formula|defaultFormula|visibleIf|requiredIf|readonlyIf|rule|condition)$";

    /** A shown field of the form as a formula reads it. */
    @Schema(name = "StudioFormulaField", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Field(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = FIELD_ID)
            @NotBlank
            @Pattern(regexp = FIELD_ID)
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
                        "url",
                        "select",
                        "ref",
                        "user",
                        "file"
                    })
            @NotBlank
            @Pattern(regexp = "^(text|textarea|number|date|datetime|time|bool|email|phone|url|select|ref|user|file)$")
            String type,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "What a ref field names: a list keeps a code, a form a row's key")
            @Nullable
            @Pattern(regexp = "^(list|form)$")
            String target,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The field is computed: a formula of a line does not read it (ADR-0043)")
            @Nullable
            Boolean computed) {
        /** A field as formulas of the header read it. */
        public Field(String id, String type, @Nullable String target) {
            this(id, type, target, null);
        }
    }

    /** A table of lines of a document, as the formulas read its columns (ADR-0043). */
    @Schema(name = "StudioFormulaLines", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Lines(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = FIELD_ID)
            @NotBlank
            @Pattern(regexp = FIELD_ID)
            String key,

            @ArraySchema(maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            @NotNull
            @Size(max = 20)
            @Valid
            List<Field> fields) {}

    @Schema(name = "StudioFormulaCheckRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CheckRequest(
            @ArraySchema(maxItems = 30, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            @NotNull
            @Size(max = 30)
            @Valid
            List<Field> fields,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000) @NotNull @Size(max = 1000)
            String formula,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = KIND) @NotBlank @Pattern(regexp = KIND)
            String kind,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The id of the field the formula belongs to; absent for a rule", pattern = FIELD_ID)
            @Nullable
            @Pattern(regexp = FIELD_ID)
            String field,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    maxItems = 3,
                    arraySchema = @Schema(description = "The tables of lines of a document (ADR-0043)"))
            @Nullable
            @Size(max = 3)
            @Valid
            List<Lines> lines,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "The table of lines whose column the formula computes: it reads the row's columns"
                            + " and the header's entered fields as doc.<field>",
                    pattern = FIELD_ID)
            @Nullable
            @Pattern(regexp = FIELD_ID)
            String line,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The document has its number (docNumber)")
            @Nullable
            Boolean numbered,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The document has its statuses (docStatus)")
            @Nullable
            Boolean statuses) {
        /** A check of a formula of a form that is no document. */
        public CheckRequest(List<Field> fields, String formula, String kind, @Nullable String field) {
            this(fields, formula, kind, field, null, null, null, null);
        }
    }

    /**
     * The outcome: {@code valid} with the formula's {@code type}, or the {@code reason} of its first problem, the
     * 1-based {@code position} of the character it is at, the {@code name} it concerns and the catalog key of its text.
     */
    @Schema(name = "StudioFormulaCheck")
    public record Check(
            boolean valid,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String type,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String reason,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Integer position,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String name,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String messageKey) {}
}
