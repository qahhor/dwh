package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The parameters of the types of plan 10/10, item 5.2 on a form field (ADR-0032, 4.1–4.2), as {@code form-meta} gives
 * them to the form: the scale of a number, the item count of a multiple reference, the size and types of a file, the
 * currencies of money, the reference entity of an enumeration and the root of JSON.
 *
 * @param scale        most digits after the point, or null
 * @param maxItems     most keys of a multiple reference, or null
 * @param maxBytes     largest file, or null
 * @param contentTypes the content types a file may have; empty takes any
 * @param currencies   the currencies money may be in; empty for other types
 * @param enumeration  the code of an enumeration's reference entity, or null
 * @param jsonRoot     the root JSON must have, or null for an object or an array
 * @param currencyFrom the key of the select field whose value is the currency of money (ADR-0032, 9.1), or null
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FieldParams(
        @Nullable Integer scale,
        @Nullable Integer maxItems,
        @Nullable Long maxBytes,
        List<String> contentTypes,
        List<String> currencies,
        @Nullable String enumeration,
        FieldOptions.@Nullable JsonRoot jsonRoot,
        @Nullable String currencyFrom) {

    /** No parameters. */
    public static final FieldParams NONE = new FieldParams(null, null, null, List.of(), List.of(), null, null);

    public FieldParams {
        contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
    }

    /** Parameters whose money keeps its currency itself. */
    public FieldParams(
            @Nullable Integer scale,
            @Nullable Integer maxItems,
            @Nullable Long maxBytes,
            List<String> contentTypes,
            List<String> currencies,
            @Nullable String enumeration,
            FieldOptions.@Nullable JsonRoot jsonRoot) {
        this(scale, maxItems, maxBytes, contentTypes, currencies, enumeration, jsonRoot, null);
    }

    /** The parameters a field's rules and options give. */
    public static FieldParams of(FieldRules rules, FieldOptions options) {
        return new FieldParams(
                rules.scale(),
                rules.maxItems(),
                rules.maxBytes(),
                rules.contentTypes(),
                options.currencies(),
                options.enumeration(),
                options.jsonRoot(),
                options.currencyFrom());
    }
}
