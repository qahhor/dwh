package com.smartup24.cms.instance.support.entity;

import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldOptions.JsonRoot;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;

/**
 * Values of entity fields made up from their declaration (ADR-0032, 11.2): a valid value of each type that needs no
 * other row, a changed value for the update, and the invalid values of each rule with the code the server's check
 * ({@link EntityValidator}, {@link FieldValueRules}) answers with. {@code EntitySamplesTest} proves the derivation on
 * the test entity with a field of every type, so the kit's validation cases cover every type the matrix covers.
 */
public final class EntitySamples {

    /**
     * An invalid value of a field.
     *
     * @param field the field's key
     * @param value the value sent
     * @param code  the code of the problem the server answers with
     * @param typed the value has the JSON type of the field (a text for a text, a number for a number), so a module's
     *              typed request reaches the server's check with it; a value of another type is refused earlier
     */
    public record Sample(String field, @Nullable Object value, String code, boolean typed) {}

    private static final String NOT_AN_OPTION = "kit-not-an-option";

    private EntitySamples() {}

    /** Whether a save writes the field: a stored source, on the form, not computed and not always read-only. */
    public static boolean writable(EntityField field) {
        FormField form = field.formField();
        if (form == null || !field.source().writable() || field.source() instanceof FieldSource.Computed) {
            return false;
        }
        FieldReadonly readonly = form.flags().readonly();
        return readonly == null || readonly.mode() != FieldReadonly.Mode.ALWAYS;
    }

    /** Whether an update may change the field: written, and not read-only once the record exists. */
    public static boolean updatable(EntityField field) {
        FieldReadonly readonly =
                Objects.requireNonNull(field.formField()).flags().readonly();
        return writable(field) && readonly == null;
    }

    /**
     * A valid value of the field holding {@code token}, or empty when only the entity's fixture can give one: a
     * reference, an enumeration item, a file, or a text whose pattern the token does not match.
     */
    public static Optional<Object> valid(FormField field, String token) {
        return switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN -> text(field, token);
            case NUMBER -> Optional.of(number(field));
            case DATE -> Optional.of("2026-10-01");
            case DATETIME -> Optional.of("2026-10-01T09:30:00Z");
            case TIME -> Optional.of("09:30");
            case BOOLEAN -> Optional.of(true);
            case SELECT -> Optional.of(field.options().getLast());
            case EMAIL -> Optional.of(token + "@example.com");
            case PHONE -> Optional.of(phone(token));
            case URL -> Optional.of("https://example.com/" + token);
            case MONEY ->
                Optional.of(Map.of(
                        "amount",
                        number(field),
                        "currency",
                        field.params().currencies().getFirst()));
            case JSON ->
                Optional.of(field.params().jsonRoot() == JsonRoot.ARRAY ? List.of(token) : Map.of("kit", token));
            case REF, ENUM, MULTI_REF, FILE, IMAGE -> Optional.empty();
        };
    }

    /** A valid value different from {@code valid}, for the update; empty when none can be made up. */
    public static Optional<Object> changed(FormField field, String token) {
        return switch (field.type()) {
            case BOOLEAN -> Optional.of(false);
            case SELECT ->
                field.options().size() > 1 ? Optional.of(field.options().getFirst()) : Optional.empty();
            case DATE -> Optional.of("2026-10-02");
            case DATETIME -> Optional.of("2026-10-02T10:45:00Z");
            case TIME -> Optional.of("10:45");
            case PHONE -> Optional.of(phone(token + "-changed"));
            case NUMBER -> otherNumber(field).map(Object.class::cast);
            case MONEY ->
                otherNumber(field).map(amount -> (Object) Map.of(
                        "amount",
                        amount,
                        "currency",
                        field.params().currencies().getFirst()));
            default -> valid(field, token);
        };
    }

    /** The invalid values of the field's rules and of its type, each with the code it is refused with. */
    public static List<Sample> invalid(FormField field) {
        List<Sample> samples = new ArrayList<>();
        String key = field.key();
        switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN -> textRules(field, samples);
            case NUMBER -> {
                samples.add(new Sample(key, "not-a-number", EntityValidator.INVALID, false));
                numberRules(field, samples);
            }
            case DATE -> samples.add(new Sample(key, "01.10.2026", EntityValidator.INVALID, true));
            case DATETIME -> samples.add(new Sample(key, "2026-10-01 09:30", EntityValidator.INVALID, true));
            case TIME -> samples.add(new Sample(key, "25:00", EntityValidator.INVALID, true));
            case BOOLEAN -> samples.add(new Sample(key, "maybe", EntityValidator.INVALID, false));
            case SELECT -> samples.add(new Sample(key, NOT_AN_OPTION, EntityValidator.INVALID, true));
            case REF -> samples.add(new Sample(key, List.of(1L), EntityValidator.INVALID, false));
            case EMAIL -> samples.add(new Sample(key, "kit@", EntityValidator.INVALID, true));
            case PHONE -> samples.add(new Sample(key, "8901234567", EntityValidator.INVALID, true));
            case URL -> samples.add(new Sample(key, "ftp://example.com", EntityValidator.INVALID, true));
            case MONEY -> samples.add(new Sample(key, money(field), EntityValidator.INVALID, true));
            case ENUM -> samples.add(new Sample(key, 7, EntityValidator.INVALID, false));
            case MULTI_REF -> multiRefRules(field, samples);
            case FILE, IMAGE -> samples.add(new Sample(key, "not-a-uuid", EntityValidator.INVALID, true));
            case JSON ->
                samples.add(new Sample(
                        key,
                        field.params().jsonRoot() == JsonRoot.ARRAY ? Map.of("a", 1) : List.of(1, 2),
                        EntityValidator.INVALID,
                        true));
        }
        return samples;
    }

    /**
     * Whether the value read back is the value sent: by the server's own comparison ({@link FieldValueRules#same}),
     * a moment by its instant and a time of day by its value, whatever text form the record reads them in.
     */
    public static boolean same(FormField field, @Nullable Object sent, @Nullable Object read) {
        if (sent != null && read != null) {
            try {
                if (field.type() == FieldType.DATETIME) {
                    return OffsetDateTime.parse(String.valueOf(sent))
                            .toInstant()
                            .equals(OffsetDateTime.parse(String.valueOf(read)).toInstant());
                }
                if (field.type() == FieldType.TIME) {
                    return LocalTime.parse(String.valueOf(sent)).equals(LocalTime.parse(String.valueOf(read)));
                }
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return FieldValueRules.same(field, sent, read);
    }

    private static Optional<Object> text(FormField field, String token) {
        String text = token;
        if (field.maxLength() != null && text.length() > field.maxLength()) {
            text = text.substring(text.length() - field.maxLength());
        }
        if (field.minLength() != null && text.length() < field.minLength()) {
            text = text + "x".repeat(field.minLength() - text.length());
        }
        if (field.pattern() != null && !Pattern.matches(field.pattern(), text)) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /**
     * A phone number in E.164 made from the token, so two records of one run hold different numbers: an entity may
     * keep a phone unique (one active user per phone).
     */
    private static String phone(String token) {
        long digits = Math.floorMod(token.hashCode(), 10_000_000L);
        return "+99890" + String.format(java.util.Locale.ROOT, "%07d", digits);
    }

    private static String number(FormField field) {
        BigDecimal value = BigDecimal.valueOf(7);
        if (field.min() != null && value.compareTo(field.min()) < 0) value = field.min();
        if (field.max() != null && value.compareTo(field.max()) > 0) value = field.max();
        return value.toPlainString();
    }

    /** A number in the field's range other than {@link #number}, or empty when the range holds one number. */
    private static Optional<String> otherNumber(FormField field) {
        BigDecimal base = new BigDecimal(number(field));
        for (BigDecimal candidate : List.of(base.add(BigDecimal.ONE), base.subtract(BigDecimal.ONE))) {
            boolean inRange = (field.min() == null || candidate.compareTo(field.min()) >= 0)
                    && (field.max() == null || candidate.compareTo(field.max()) <= 0);
            if (inRange) return Optional.of(candidate.toPlainString());
        }
        return Optional.empty();
    }

    private static void textRules(FormField field, List<Sample> samples) {
        String key = field.key();
        if (field.maxLength() != null) {
            samples.add(new Sample(key, "x".repeat(field.maxLength() + 1), EntityValidator.TOO_LONG, true));
        }
        if (field.minLength() != null && field.minLength() > 1) {
            samples.add(new Sample(key, "x".repeat(field.minLength() - 1), EntityValidator.TOO_SHORT, true));
        }
        String mismatch = "Kit value ?!";
        if (field.pattern() != null
                && !Pattern.matches(field.pattern(), mismatch)
                && (field.maxLength() == null || mismatch.length() <= field.maxLength())) {
            samples.add(new Sample(key, mismatch, EntityValidator.INVALID, true));
        }
    }

    private static void numberRules(FormField field, List<Sample> samples) {
        String key = field.key();
        if (field.max() != null) {
            samples.add(new Sample(
                    key, field.max().add(BigDecimal.ONE).toPlainString(), EntityValidator.OUT_OF_RANGE, true));
        }
        if (field.min() != null) {
            samples.add(new Sample(
                    key, field.min().subtract(BigDecimal.ONE).toPlainString(), EntityValidator.OUT_OF_RANGE, true));
        }
        Integer scale = field.params().scale();
        if (scale != null) {
            samples.add(new Sample(key, "1." + "1".repeat(scale + 1), EntityValidator.INVALID, true));
        }
    }

    private static void multiRefRules(FormField field, List<Sample> samples) {
        String key = field.key();
        samples.add(new Sample(key, List.of(1L, 1L), EntityValidator.INVALID, true));
        int most = field.params().maxItems() == null
                ? FieldValueRules.DEFAULT_MAX_ITEMS
                : field.params().maxItems();
        samples.add(
                new Sample(key, LongStream.rangeClosed(1, most + 1L).boxed().toList(), FieldValueRules.TOO_MANY, true));
    }

    /** Money in a currency the field does not take. */
    private static Map<String, Object> money(FormField field) {
        String currency = field.params().currencies().contains("EUR") ? "JPY" : "EUR";
        return Map.of("amount", "10", "currency", currency);
    }
}
