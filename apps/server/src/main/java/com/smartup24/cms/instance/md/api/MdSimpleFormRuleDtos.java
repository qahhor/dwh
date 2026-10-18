package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The validation rules of a live form of simple-form format 5 (ADR-0042). */
public final class MdSimpleFormRuleDtos {
    private MdSimpleFormRuleDtos() {}

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
}
