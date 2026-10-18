package com.smartup24.cms.instance.common.formula;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A checked node of a formula (ADR-0042), the form the server evaluates and the web interprets with the same rules:
 * {@code literal} ({@code value} is the canonical text, absent for {@code null}), {@code field} ({@code name} is the
 * field key), {@code unary} ({@code -}, {@code not}), {@code binary} ({@code + - * / & = != < <= > >= and or}) and
 * {@code call} ({@code name} is the function). {@code type} is the node's static type.
 */
@Schema(name = "FormulaNode", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record FormulaNode(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"literal", "field", "unary", "binary", "call"})
        String kind,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "The operator, function or field key")
        @Nullable
        String name,

        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The canonical text of a literal") @Nullable
        String value,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {
                    "number", "text", "bool", "date", "datetime", "time", "option", "ref", "null", "numbers", "rows"
                })
        String type,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @ArraySchema(arraySchema = @Schema(description = "The operands or arguments, in order"))
        @Nullable
        List<FormulaNode> args) {

    public static final String LITERAL = "literal";
    public static final String FIELD = "field";
    public static final String UNARY = "unary";
    public static final String BINARY = "binary";
    public static final String CALL = "call";

    public FormulaNode {
        args = args == null ? null : List.copyOf(args);
    }

    /** The node's static type. */
    @JsonIgnore
    public FormulaType formulaType() {
        return FormulaType.of(type);
    }

    /** The operands or arguments; empty for a literal and a field. */
    @JsonIgnore
    public List<FormulaNode> operands() {
        return args == null ? List.of() : args;
    }

    static FormulaNode literal(FormulaType type, @Nullable String value) {
        return new FormulaNode(LITERAL, null, value, type.wire(), null);
    }

    static FormulaNode field(String key, FormulaType type) {
        return new FormulaNode(FIELD, key, null, type.wire(), null);
    }

    static FormulaNode of(String kind, String name, FormulaType type, List<FormulaNode> args) {
        return new FormulaNode(kind, name, null, type.wire(), args);
    }
}
