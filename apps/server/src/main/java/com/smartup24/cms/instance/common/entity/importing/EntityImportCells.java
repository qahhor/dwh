package com.smartup24.cms.instance.common.entity.importing;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.platform.api.entity.field.FieldOptions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A cell of an import file read as the value of its field in the body of a save (ADR-0032, 6.2 and 10.1): what a
 * spreadsheet keeps — a number, a date as the days Excel counts, a flag, a text — becomes what the API takes, and the
 * runtime then checks it as any value sent. A choice takes its code or the name the template shows for it; a flag
 * takes yes and no in the product's languages; a date or time typed as text is passed as it is.
 */
public final class EntityImportCells {

    /** The message key of a cell the field cannot take at all (a flag that says neither yes nor no). */
    public static final String VALUE_TYPE_KEY = "error.field.value_type";

    /** The day Excel counts dates from (its 1900 system, after its leap-year slip). */
    private static final LocalDateTime EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    private static final long SECONDS_A_DAY = 86_400L;

    private static final Set<String> YES = Set.of("true", "yes", "y", "1", "да", "д", "ha", "x", "+");
    private static final Set<String> NO = Set.of("false", "no", "n", "0", "нет", "н", "yo'q", "yoʻq", "-");

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("^-?\\d+,\\d+$");
    private static final Pattern AMOUNT_AND_CURRENCY = Pattern.compile("^(-?[0-9.,\\s]+)\\s*([A-Za-z]{3})$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private EntityImportCells() {}

    /** A cell read: the value for the body, or the problem of the cell. */
    public record Cell(@Nullable Object value, @Nullable FieldErrorItem problem) {
        static Cell of(@Nullable Object value) {
            return new Cell(value, null);
        }

        static Cell refused(String key) {
            return new Cell(null, FieldErrorItem.keyed(key, EntityValidator.INVALID, VALUE_TYPE_KEY));
        }
    }

    /**
     * The value of {@code raw} for the field of the column.
     *
     * @param column  the template's column of the field
     * @param options the parameters of the field's type: the currency of its money
     * @param raw     the cell: a text, a {@code BigDecimal} or a {@code Boolean}
     */
    public static Cell read(EntityImporter.Column column, FieldOptions options, Object raw) {
        String key = column.key();
        return switch (column.type()) {
            case NUMBER -> Cell.of(number(raw));
            case BOOLEAN -> flag(key, raw);
            case DATE ->
                Cell.of(
                        raw instanceof BigDecimal days
                                ? moment(days).toLocalDate().toString()
                                : text(raw));
            case DATETIME ->
                Cell.of(
                        raw instanceof BigDecimal days
                                ? moment(days).atOffset(ZoneOffset.UTC).toString()
                                : text(raw));
            case TIME -> Cell.of(raw instanceof BigDecimal days ? time(days) : text(raw));
            case SELECT, ENUM -> Cell.of(choice(column, text(raw)));
            case REF -> Cell.of(raw instanceof BigDecimal whole ? reference(whole) : text(raw));
            case MULTI_REF -> references(key, raw);
            case MONEY -> Cell.of(money(options, raw));
            default -> Cell.of(text(raw));
        };
    }

    /** A cell as text: a whole number without a fraction, a flag as {@code true} or {@code false}. */
    static String text(Object raw) {
        if (raw instanceof BigDecimal number) return plain(number);
        return String.valueOf(raw).strip();
    }

    private static Object number(Object raw) {
        if (raw instanceof BigDecimal number) return plain(number);
        String text = text(raw);
        return DECIMAL_COMMA.matcher(text).matches() ? text.replace(',', '.') : text;
    }

    private static Cell flag(String key, Object raw) {
        if (raw instanceof Boolean flag) return Cell.of(flag);
        String word = text(raw).toLowerCase(Locale.ROOT);
        if (YES.contains(word)) return Cell.of(true);
        if (NO.contains(word)) return Cell.of(false);
        return Cell.refused(key);
    }

    /** The code of a choice: the code itself, or the code whose name the cell gives (in any letter case). */
    private static String choice(EntityImporter.Column column, String text) {
        for (EntityImporter.Option option : column.options()) {
            if (option.code().equals(text)) return text;
        }
        for (EntityImporter.Option option : column.options()) {
            if (option.label() != null && option.label().equalsIgnoreCase(text)) return option.code();
        }
        return text;
    }

    private static Object reference(BigDecimal whole) {
        try {
            return whole.longValueExact();
        } catch (ArithmeticException notWhole) {
            return plain(whole);
        }
    }

    private static Cell references(String key, Object raw) {
        if (raw instanceof BigDecimal whole) return Cell.of(List.of(reference(whole)));
        List<Long> keys = new ArrayList<>();
        for (String part : SEPARATORS.split(text(raw))) {
            if (part.isEmpty()) continue;
            try {
                keys.add(Long.parseLong(part));
            } catch (NumberFormatException notKey) {
                return Cell.refused(key);
            }
        }
        return Cell.of(keys);
    }

    /**
     * Money: the amount alone when its currency is the document's or the field has one currency; otherwise the amount
     * and the currency's code in one cell ({@code 1250.00 UZS}).
     */
    private static Object money(FieldOptions options, Object raw) {
        if (options.currencyFrom() != null) return number(raw);
        Map<String, Object> money = new LinkedHashMap<>();
        String text = text(raw);
        var found = AMOUNT_AND_CURRENCY.matcher(text);
        if (!(raw instanceof BigDecimal) && found.matches()) {
            money.put("amount", number(found.group(1).replaceAll("\\s", "")));
            money.put("currency", found.group(2).toUpperCase(Locale.ROOT));
            return money;
        }
        money.put("amount", number(raw));
        if (options.currencies().size() == 1)
            money.put("currency", options.currencies().getFirst());
        return money;
    }

    /** The moment of a number of days Excel counts, to the second. */
    static LocalDateTime moment(BigDecimal days) {
        long seconds = days.multiply(BigDecimal.valueOf(SECONDS_A_DAY))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return EXCEL_EPOCH.plusSeconds(seconds);
    }

    private static String time(BigDecimal days) {
        LocalDateTime moment = moment(days.remainder(BigDecimal.ONE));
        return moment.getSecond() == 0 ? moment.format(TIME) : moment.format(TIME_SECONDS);
    }

    private static String plain(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
