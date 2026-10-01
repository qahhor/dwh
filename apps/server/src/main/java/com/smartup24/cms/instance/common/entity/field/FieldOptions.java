package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.query.QueryRef;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The parameters of a field's type (ADR-0032, 3.1): the options of a select and the dictionary prefix of their labels,
 * the source of a reference, the currencies of money, the reference entity of an enumeration and the root of JSON.
 *
 * @param options           the values a select offers, in order
 * @param optionLabelPrefix dictionary prefix of the option labels ({@code notes.color_}); null shows the value
 * @param ref               where a reference's rows are picked from (ADR-0019, 2.4), or null
 * @param currencies        the ISO 4217 codes money may be in, the first the one offered first; empty for other types
 * @param enumeration       the code of the reference entity an enumeration's items come from (ADR-0032, 4.5), or null
 * @param jsonRoot          what the root of a JSON value must be, or null for an object or an array
 */
public record FieldOptions(
        List<String> options,
        @Nullable String optionLabelPrefix,
        @Nullable QueryRef ref,
        List<String> currencies,
        @Nullable String enumeration,
        @Nullable JsonRoot jsonRoot) {

    /** No parameters. */
    public static final FieldOptions NONE = new FieldOptions(List.of(), null, null, List.of(), null, null);

    /** The root a JSON value must have. */
    public enum JsonRoot {
        OBJECT,
        ARRAY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public FieldOptions {
        options = List.copyOf(options);
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
        for (String currency : currencies) {
            if (!FieldSource.CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("Bad currency: " + currency);
            }
        }
    }

    /** A select's options. */
    public static FieldOptions choice(List<String> options, @Nullable String optionLabelPrefix) {
        return new FieldOptions(options, optionLabelPrefix, null, List.of(), null, null);
    }

    /** A reference's source. */
    public static FieldOptions refersTo(QueryRef source) {
        return new FieldOptions(List.of(), null, source, List.of(), null, null);
    }

    /** Money's currencies. */
    public static FieldOptions money(List<String> currencies) {
        return new FieldOptions(List.of(), null, null, currencies, null, null);
    }

    /** An enumeration's reference entity. */
    public static FieldOptions enumeration(String reference) {
        return new FieldOptions(List.of(), null, null, List.of(), reference, null);
    }

    /** JSON with this root, or either. */
    public static FieldOptions json(@Nullable JsonRoot root) {
        return new FieldOptions(List.of(), null, null, List.of(), null, root);
    }
}
