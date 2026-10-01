package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.query.QueryField;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.dhatim.fastexcel.HyperLink;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cells of the entity field types that the list type alone does not describe (ADR-0032, 4.1; the list field's
 * {@code format}): money as a number in its currency's format, an address as a link, an enumeration by its item's
 * name, several references as their keys, a file by its name, JSON as its text. Every type of plan 10/10, item 5.2
 * has a cell here or in {@link ExportWorkbookWriter} ({@code FieldTypeMatrixTest}).
 */
final class ExportFormats {

    /** The most characters an xlsx cell holds. */
    static final int MAX_CELL = 32_767;

    /** Writes JSON with default settings only: the shared default mapper (plan 10/10, item 3.11). */
    private static final JsonMapper JSON = JsonMapper.shared();

    private ExportFormats() {}

    /** The item's value of the field: the currency field of money reads the currency of its money. */
    static @Nullable Object value(QueryField field, Map<String, Object> item) {
        if ("currency".equals(field.format()) && field.key().endsWith("Currency")) {
            Object money = item.get(field.key().substring(0, field.key().length() - "Currency".length()));
            return money instanceof Map<?, ?> amount ? amount.get("currency") : null;
        }
        return item.get(field.key());
    }

    /** The cell of a formatted field; false when the field has no format of its own and the list type decides. */
    static boolean write(Worksheet sheet, int row, int c, QueryField field, Object value) {
        String format = field.format();
        if (format == null) return false;
        switch (format) {
            case "money" -> money(sheet, row, c, value);
            case "url" -> link(sheet, row, c, String.valueOf(value));
            case "enum" -> sheet.value(row, c, enumName(field, String.valueOf(value)));
            case "multi_ref" ->
                sheet.value(
                        row,
                        c,
                        value instanceof Collection<?> keys
                                ? keys.stream().map(String::valueOf).collect(Collectors.joining(", "))
                                : String.valueOf(value));
            case "file", "image" ->
                sheet.value(
                        row,
                        c,
                        value instanceof Map<?, ?> file ? String.valueOf(file.get("name")) : String.valueOf(value));
            case "json" -> sheet.value(row, c, cut(json(value)));
            default -> sheet.value(row, c, String.valueOf(value));
        }
        return true;
    }

    /** The number format of an amount in {@code currency}: its digits after the point and its code. */
    static String moneyFormat(String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return "#,##0" + (digits > 0 ? "." + "0".repeat(digits) : "") + " \"" + currency + "\"";
    }

    private static void money(Worksheet sheet, int row, int c, Object value) {
        if (!(value instanceof Map<?, ?> money)) {
            sheet.value(row, c, String.valueOf(value));
            return;
        }
        String currency = Objects.toString(money.get("currency"), "");
        try {
            sheet.value(row, c, new BigDecimal(String.valueOf(money.get("amount"))));
            if (currency.matches("^[A-Z]{3}$")) {
                sheet.style(row, c).format(moneyFormat(currency)).set();
            }
        } catch (NumberFormatException e) {
            sheet.value(row, c, money.get("amount") + " " + currency);
        }
    }

    /** An address as a link, only {@code http} and {@code https}; anything else as text. */
    private static void link(Worksheet sheet, int row, int c, String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            sheet.hyperlink(row, c, HyperLink.external(url, url));
        } else {
            sheet.value(row, c, url);
        }
    }

    private static String enumName(QueryField field, String value) {
        Map<String, String> labels = field.enumLabels();
        return labels != null && labels.containsKey(value) ? labels.get(value) : value;
    }

    private static String json(Object value) {
        if (value instanceof String text) return text;
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private static String cut(String text) {
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) : text;
    }
}
