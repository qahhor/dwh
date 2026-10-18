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
            String titleField) {
        /** An entity of format 1, which has no menu of its own. */
        public Entity(String name, Labels title) {
            this(name, title, null, null, null);
        }

        /** An entity of format 2, which sees every record. */
        public Entity(String name, Labels title, @Nullable Menu menu) {
            this(name, title, menu, null, null);
        }

        /** An entity of format 3, named by its system name field. */
        public Entity(String name, Labels title, @Nullable Menu menu, @Nullable String scope) {
            this(name, title, menu, scope, null);
        }
    }

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
            @Schema(
                    description = "Format 5: the formula of a computed field (ADR-0042) - read-only, recomputed and"
                            + " stored on every save",
                    minLength = 1,
                    maxLength = 1000)
            @Nullable
            String formula,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 5: the formula of the value a new record takes when it is sent none",
                    minLength = 1,
                    maxLength = 1000)
            @Nullable
            String defaultFormula,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 5: the field is shown only while this yes/no formula is true; a hidden field"
                            + " keeps no value and is not required",
                    minLength = 1,
                    maxLength = 1000)
            @Nullable
            String visibleIf,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 5: an optional field is required while this yes/no formula is true",
                    minLength = 1,
                    maxLength = 1000)
            @Nullable
            String requiredIf,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "Format 5: a saved record's field cannot be changed while this yes/no formula is"
                            + " true over the record as it is stored",
                    minLength = 1,
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

        /** Whether the field has a formula of format 5. */
        public boolean hasLogic() {
            return formula != null
                    || defaultFormula != null
                    || visibleIf != null
                    || requiredIf != null
                    || readonlyIf != null;
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
                    maximum = "5",
                    description = "1; 2 for a live form with hidden fields, defaults or its own menu item; 3 adds"
                            + " select, ref and user fields and the data scope; 4 adds the layout and the title"
                            + " field; 5 adds formulas and rules")
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
            List<Rule> rules) {
        /** A declaration of formats 1 to 3, which has no layout of its own. */
        public Declaration(int formatVersion, String kind, Module module, Entity entity, List<Field> fields) {
            this(formatVersion, kind, module, entity, fields, null, null);
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

    /** The message of a rule, from format 5 on (ADR-0042): what the person who saves reads when the rule fails. */
    @Schema(name = "SimpleFormRuleMessage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RuleMessage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
            String ru,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
            String uz,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
            String en) {}

    /**
     * A validation rule of a live form, from format 5 on (ADR-0042): a yes/no formula every save must not make false
     * (an empty value passes), its message and, optionally, the field the problem is shown at.
     */
    @Schema(name = "SimpleFormRule", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Rule(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{0,19}$")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 1000)
            String formula,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            RuleMessage message,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The id of the shown field the problem is addressed to; absent: the whole record")
            @Nullable
            String field) {}

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
