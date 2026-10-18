package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Layout;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.Rule;
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
            Menu menu,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 3: which records a viewer sees (ADR-0041) - all, those of the viewer's org"
                            + " units (org_unit) or the viewer's own (owner); absent means all",
                    allowableValues = {"all", "org_unit", "owner"})
            @Nullable
            String scope,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 4: the field that names a record (ADR-0041) - a required, listed text field,"
                            + " or name for the system name and code fields; absent means the record's number",
                    pattern = "^[a-z][a-z0-9]{0,19}$")
            @Nullable
            String titleField,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 6: the number of a document (ADR-0043)")
            @Nullable
            Numbering numbering) {
        /** An entity of format 1, which has no menu of its own. */
        public Entity(String name, Labels title) {
            this(name, title, null, null, null, null);
        }

        /** An entity of format 2, which sees every record. */
        public Entity(String name, Labels title, @Nullable Menu menu) {
            this(name, title, menu, null, null, null);
        }

        /** An entity of format 3, named by its system name field. */
        public Entity(String name, Labels title, @Nullable Menu menu, @Nullable String scope) {
            this(name, title, menu, scope, null, null);
        }

        /** An entity of formats 4 and 5, which has no number of its own. */
        public Entity(
                String name, Labels title, @Nullable Menu menu, @Nullable String scope, @Nullable String titleField) {
            this(name, title, menu, scope, titleField, null);
        }
    }

    /** A fixed choice of a select field, from format 3 on (ADR-0041): its code is what the records keep. */
    @Schema(name = "SimpleFormOption", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Option(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z0-9][a-z0-9_-]{0,39}$")
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels) {}

    /**
     * What a reference field names, from format 3 on (ADR-0041): an item of a reference list ({@code list}, by the
     * list's code) or a record of another live form ({@code form}, by its code {@code studio.<name>}).
     */
    @Schema(name = "SimpleFormTarget", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Target(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"list", "form"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 40)
            String code) {}

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
            String defaultValue,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    maxItems = 50,
                    arraySchema = @Schema(description = "Format 3: the choices of a select field, in their order"))
            @Nullable
            List<Option> options,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 3: what a ref field names")
            @Nullable
            Target target,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 5: a computed field's formula (ADR-0042)", maxLength = 1000)
            @Nullable
            String formula,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 5: the formula of a new record's default", maxLength = 1000)
            @Nullable
            String defaultFormula,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 5: shown only while this formula is true", maxLength = 1000)
            @Nullable
            String visibleIf,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 5: required while this formula is true", maxLength = 1000)
            @Nullable
            String requiredIf,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 5: read-only while this formula is true of the stored record",
                    maxLength = 1000)
            @Nullable
            String readonlyIf) {
        /** A field of formats 3 and 4: without formulas. */
        public Field(
                String id,
                String type,
                Labels labels,
                boolean required,
                boolean listed,
                @Nullable Integer maxLength,
                @Nullable Integer scale,
                @Nullable Boolean hidden,
                @Nullable String defaultValue,
                @Nullable List<Option> options,
                @Nullable Target target) {
            this(
                    id,
                    type,
                    labels,
                    required,
                    listed,
                    maxLength,
                    scale,
                    hidden,
                    defaultValue,
                    options,
                    target,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

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

        /** A field of format 2: without choices or a target. */
        public Field(
                String id,
                String type,
                Labels labels,
                boolean required,
                boolean listed,
                @Nullable Integer maxLength,
                @Nullable Integer scale,
                @Nullable Boolean hidden,
                @Nullable String defaultValue) {
            this(id, type, labels, required, listed, maxLength, scale, hidden, defaultValue, null, null);
        }
    }

    @Schema(name = "SimpleFormDeclaration", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Declaration(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minimum = "1",
                    maximum = "6",
                    description = "1; 2 for a live form with hidden fields, defaults or its own menu item; 3 adds"
                            + " select, ref and user fields and the data scope; 4 adds the layout and the title"
                            + " field; 5 adds formulas and rules; 6 adds lines, the number and statuses")
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
            List<Field> fields,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Format 4: sections and tabs") @Nullable
            Layout layout,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    minItems = 1,
                    maxItems = 20,
                    arraySchema =
                            @Schema(
                                    description = "Format 5: the validation rules every save of a record must meet"
                                            + " (ADR-0042)"))
            @Nullable
            List<Rule> rules,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    minItems = 1,
                    maxItems = 3,
                    arraySchema = @Schema(description = "Format 6: the tables of the document's lines (ADR-0043)"))
            @Nullable
            List<Lines> lines,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Format 6: the statuses and transitions of the document (ADR-0043)")
            @Nullable
            Workflow workflow) {
        /** A declaration of formats 1 to 3, which has no layout of its own. */
        public Declaration(int formatVersion, String kind, Module module, Entity entity, List<Field> fields) {
            this(formatVersion, kind, module, entity, fields, null, null);
        }

        /** A declaration of format 5, which is no document. */
        public Declaration(
                int formatVersion,
                String kind,
                Module module,
                Entity entity,
                List<Field> fields,
                @Nullable Layout layout,
                @Nullable List<Rule> rules) {
            this(formatVersion, kind, module, entity, fields, layout, rules, null, null);
        }

        /** A declaration of format 4, which has no rules. */
        public Declaration(
                int formatVersion,
                String kind,
                Module module,
                Entity entity,
                List<Field> fields,
                @Nullable Layout layout) {
            this(formatVersion, kind, module, entity, fields, layout, null);
        }
    }

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
