package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The document parts of a live form of simple-form format 6 (ADR-0043): the tables of its lines, the number of its
 * records and its statuses with their transitions.
 */
public final class MdSimpleFormDocumentDtos {
    private MdSimpleFormDocumentDtos() {}

    /** A column of a table of lines, from format 6 on (ADR-0043): a value of one of the field types, or a formula. */
    @Schema(name = "SimpleFormLineField", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record LineField(
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
                        "url",
                        "select",
                        "ref",
                        "user"
                    })
            String type,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean required,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(minimum = "1", maximum = "4000") @Nullable
            Integer maxLength,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(minimum = "0", maximum = "6") @Nullable
            Integer scale,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(maxItems = 50, arraySchema = @Schema(description = "The choices of a select column"))
            @Nullable
            List<Option> options,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "What a ref column names") @Nullable
            Target target,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "A computed column's formula over the row and the header's entered fields"
                            + " (doc.<field>)",
                    maxLength = 1000)
            @Nullable
            String formula) {}

    /** A table of lines of a document, from format 6 on (ADR-0043), kept apart from the record by its place. */
    @Schema(name = "SimpleFormLines", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Lines(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9]{0,19}$")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "500")
            int maxRows,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 20,
                    arraySchema =
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The columns, in order"))
            List<LineField> fields) {}

    /**
     * The number of a document, from format 6 on (ADR-0043): a pattern of fixed text and the tokens {@code {YYYY}},
     * {@code {YY}}, {@code {MM}} and one {@code {N...}} of 1 to 12 digits, the period its counter starts again in, and
     * optionally the status that gives the number instead of the creation.
     */
    @Schema(name = "SimpleFormNumbering", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Numbering(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minLength = 3,
                    maxLength = 60,
                    example = "ORD-{YYYY}-{NNNNNN}")
            String pattern,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"never", "year", "month"})
            String reset,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The code of the status whose first entry gives the number; absent: the creation")
            @Nullable
            String assignOn,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The name of the number field; absent: the platform's own")
            @Nullable
            Labels labels) {}

    /** A status of a document, from format 6 on (ADR-0043): the first one is where a record starts. */
    @Schema(name = "SimpleFormState", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record State(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"gray", "blue", "green", "orange", "red", "purple"})
            String color,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "No transition leaves it and the record is only read; present only when true")
            @Nullable
            Boolean terminal,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Every field and table of lines is read-only in it; present only when true")
            @Nullable
            Boolean lockAll,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    maxItems = 50,
                    arraySchema =
                            @Schema(description = "The ids of the fields and the keys of the tables locked in it"))
            @Nullable
            List<String> locks) {}

    /**
     * A transition between statuses, from format 6 on (ADR-0043): a record action with a right of its own, an optional
     * condition in a formula, a comment, whether the record's author may take it and whether the people who may take
     * the next step are told.
     */
    @Schema(name = "SimpleFormTransition", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Transition(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 20,
                    arraySchema =
                            @Schema(
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    description = "The statuses it leaves"))
            List<String> from,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The status it reaches")
            String to,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Allowed only while this yes/no formula is true", maxLength = 1000)
            @Nullable
            String condition,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"none", "optional", "required"})
            String comment,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The record's author may not take it; present only when true")
            @Nullable
            Boolean notAuthor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "The people who may take a transition from the status it reaches get a notification;"
                            + " present only when true")
            @JsonProperty("notify")
            @Nullable
            Boolean notifies) {}

    /** The statuses of a document and the transitions between them, from format 6 on (ADR-0043). */
    @Schema(name = "SimpleFormWorkflow", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Workflow(
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The name of the status field; absent: the platform's own")
            @Nullable
            Labels labels,

            @ArraySchema(
                    minItems = 2,
                    maxItems = 20,
                    arraySchema =
                            @Schema(
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    description = "The statuses, the first one where a record starts"))
            List<State> states,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 30,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Transition> transitions) {}
}
