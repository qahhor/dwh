package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The parameters of a field's type (ADR-0032, 3.1): the options of a select and the dictionary prefix of their labels,
 * the source of a reference and the entity it names, the currencies of money, the reference entity of an enumeration
 * and the root of JSON.
 *
 * @param options           the values a select offers, in order
 * @param optionLabelPrefix dictionary prefix of the option labels ({@code notes.color_}); null shows the value
 * @param ref               where a reference's rows are picked from (ADR-0019, 2.4), or null
 * @param currencies        the ISO 4217 codes money may be in, the first the one offered first; empty for other types
 * @param enumeration       the code of the reference entity an enumeration's items come from (ADR-0032, 4.5), or null
 * @param jsonRoot          what the root of a JSON value must be, or null for an object or an array
 * @param target            the code of the entity whose rows a reference names ({@code md.users}, ADR-0032, 4.6): the
 *                          runtime checks a new value against that entity's scope and archive; null for a reference
 *                          declared by its path only
 * @param currencyFrom      the key of the select field whose value is the currency of money (ADR-0032, 9.1): a field of
 *                          the record for a computed value, a field of the document for a line of its collection; null
 *                          when money keeps its currency itself
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FieldOptions(
        List<String> options,
        @Nullable String optionLabelPrefix,
        @Nullable QueryRef ref,
        List<String> currencies,
        @Nullable String enumeration,
        @Nullable JsonRoot jsonRoot,
        @Nullable String target,
        @Nullable String currencyFrom) {

    /** No parameters. */
    public static final FieldOptions NONE = new FieldOptions(List.of(), null, null, List.of(), null, null, null, null);

    /** The root a JSON value must have. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
        if (target != null && (ref == null || !ref.path().equals(QueryRef.entityPath(target)))) {
            throw new IllegalArgumentException("A reference to " + target + " is picked from that entity's list");
        }
        if (currencyFrom != null && currencies.isEmpty()) {
            throw new IllegalArgumentException("Only money takes its currency from a field");
        }
    }

    /** Parameters whose money keeps its currency itself. */
    public FieldOptions(
            List<String> options,
            @Nullable String optionLabelPrefix,
            @Nullable QueryRef ref,
            List<String> currencies,
            @Nullable String enumeration,
            @Nullable JsonRoot jsonRoot,
            @Nullable String target) {
        this(options, optionLabelPrefix, ref, currencies, enumeration, jsonRoot, target, null);
    }

    /** The same money whose currency is the value of the select field {@code key}. */
    public FieldOptions withCurrencyFrom(String key) {
        return new FieldOptions(options, optionLabelPrefix, ref, currencies, enumeration, jsonRoot, target, key);
    }

    /** A select's options. */
    public static FieldOptions choice(List<String> options, @Nullable String optionLabelPrefix) {
        return new FieldOptions(options, optionLabelPrefix, null, List.of(), null, null, null);
    }

    /** A reference's source. */
    public static FieldOptions refersTo(QueryRef source) {
        return new FieldOptions(List.of(), null, source, List.of(), null, null, null);
    }

    /**
     * A reference to the rows of the entity {@code entity} (ADR-0032, 4.6): picked from its runtime list
     * ({@code /entities/<code>}) and named there by {@code labelField}.
     */
    public static FieldOptions targets(String entity, String labelField) {
        return new FieldOptions(
                List.of(),
                null,
                QueryRef.paged(QueryRef.entityPath(entity), labelField),
                List.of(),
                null,
                null,
                entity);
    }

    /** Money's currencies. */
    public static FieldOptions money(List<String> currencies) {
        return new FieldOptions(List.of(), null, null, currencies, null, null, null);
    }

    /** An enumeration's reference entity. */
    public static FieldOptions enumeration(String reference) {
        return new FieldOptions(List.of(), null, null, List.of(), reference, null, null);
    }

    /** JSON with this root, or either. */
    public static FieldOptions json(@Nullable JsonRoot root) {
        return new FieldOptions(List.of(), null, null, List.of(), null, root, null);
    }
}
