package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.field.FieldOptions.JsonRoot;
import com.smartup24.cms.instance.common.entity.field.FieldParams;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server's check of a value of each type added by plan 10/10, item 5.2 (ADR-0032, 4.2), and the normal form a
 * value is kept in: an e-mail in lower case, a phone without spaces, brackets and dashes. What needs the database —
 * an enumeration's item, a file — is checked by {@link EntityFieldValues}; here only the value itself.
 */
public final class FieldValueRules {

    public static final String TOO_MANY = "too_many";
    public static final String TOO_LARGE = "too_large";

    /** The most keys a multiple reference holds without {@code maxItems}. */
    public static final int DEFAULT_MAX_ITEMS = 100;

    /** The largest JSON value, in UTF-8 bytes. */
    public static final int MAX_JSON_BYTES = 64 * 1024;

    static final int MAX_EMAIL = 254;
    static final int MAX_URL = 2048;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s()\\-]");
    private static final Pattern DECIMAL = Pattern.compile("^-?[0-9]{1,15}([.][0-9]{1,6})?$");
    private static final Pattern KEY = Pattern.compile("^[0-9]{1,18}$");

    /** Reads JSON with default settings only to see its shape: the shared default mapper (plan 10/10, item 3.11). */
    private static final JsonMapper JSON = JsonMapper.shared();

    private FieldValueRules() {}

    /** The value as it is kept: an e-mail in lower case, a phone in E.164 without separators; others as they are. */
    public static Object normalize(FormField field, Object value) {
        return switch (field.type()) {
            case EMAIL -> String.valueOf(value).strip().toLowerCase(Locale.ROOT);
            case PHONE -> PHONE_SEPARATORS.matcher(String.valueOf(value)).replaceAll("");
            case URL -> String.valueOf(value).strip();
            default -> value;
        };
    }

    /** The problem of a value of a type of plan 10/10, item 5.2, or none; a value of an older type has none here. */
    static Optional<FieldErrorItem> problem(FormField field, Object value) {
        String key = field.key();
        return switch (field.type()) {
            case EMAIL -> {
                String text = String.valueOf(normalize(field, value));
                yield text.length() <= MAX_EMAIL && EMAIL.matcher(text).matches()
                        ? Optional.empty()
                        : invalid(key, "error.field.email_invalid");
            }
            case PHONE ->
                PHONE.matcher(String.valueOf(normalize(field, value))).matches()
                        ? Optional.empty()
                        : invalid(key, "error.field.phone_invalid");
            case URL ->
                isWebAddress(String.valueOf(value).strip())
                        ? Optional.empty()
                        : invalid(key, "error.field.url_invalid");
            case MONEY -> money(field, value);
            case ENUM ->
                value instanceof String code && !code.isBlank()
                        ? Optional.empty()
                        : invalid(key, "error.field.option_required");
            case MULTI_REF -> keys(field, value);
            case FILE, IMAGE -> fileId(value).isPresent() ? Optional.empty() : invalid(key, "error.field.file_invalid");
            case JSON -> json(field, value);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a value of a save is the value the record has (ADR-0032, 4.4): a file by its id, several references by
     * their keys in order, money and numbers by amount, the rest by their text in the kept form; empty equals empty.
     */
    public static boolean same(FormField field, @Nullable Object given, @Nullable Object kept) {
        if (given == null || kept == null || blank(given) || blank(kept)) return blank(given) && blank(kept);
        return switch (field.type()) {
            case FILE, IMAGE -> fileId(given).equals(fileId(kept));
            case MULTI_REF -> keyList(given).equals(keyList(kept));
            case MONEY ->
                given instanceof Map<?, ?> a
                        && kept instanceof Map<?, ?> b
                        && Objects.equals(String.valueOf(a.get("currency")), String.valueOf(b.get("currency")))
                        && sameNumber(a.get("amount"), b.get("amount"));
            case NUMBER -> sameNumber(given, kept);
            case JSON -> tree(given).equals(tree(kept));
            default -> String.valueOf(normalize(field, given)).equals(String.valueOf(normalize(field, kept)));
        };
    }

    /** A JSON value as a tree: text is read as JSON, so the text of an object and the object are one value. */
    private static JsonNode tree(Object value) {
        if (value instanceof String text) {
            try {
                return JSON.readTree(text);
            } catch (JacksonException e) {
                return JSON.getNodeFactory().textNode(text);
            }
        }
        return JSON.valueToTree(value);
    }

    private static boolean blank(@Nullable Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static boolean sameNumber(@Nullable Object a, @Nullable Object b) {
        try {
            return a != null
                    && b != null
                    && new BigDecimal(String.valueOf(a).strip())
                                    .compareTo(new BigDecimal(String.valueOf(b).strip()))
                            == 0;
        } catch (NumberFormatException e) {
            return String.valueOf(a).equals(String.valueOf(b));
        }
    }

    /** Most digits after the point of a number (ADR-0032, 3.1). */
    static Optional<FieldErrorItem> scale(String key, BigDecimal number, @Nullable Integer scale) {
        if (scale != null && number.stripTrailingZeros().scale() > scale) {
            return error(key, EntityValidator.INVALID, "error.field.scale_exceeded", Map.of("max", scale));
        }
        return Optional.empty();
    }

    /** The id of a file value: its uuid as text, or {@code {"id": ...}} as the record reads it back. */
    public static Optional<UUID> fileId(@Nullable Object value) {
        Object id = value instanceof Map<?, ?> file ? file.get("id") : value;
        if (id == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(String.valueOf(id).strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The keys of a multiple reference value, or empty when it is not a list of positive whole numbers. */
    public static Optional<List<Long>> keyList(@Nullable Object value) {
        if (!(value instanceof Collection<?> items)) return Optional.empty();
        List<Long> keys = new ArrayList<>();
        for (Object item : items) {
            Long key = key(item);
            if (key == null || key < 1) return Optional.empty();
            keys.add(key);
        }
        return Optional.of(keys);
    }

    private static @Nullable Long key(@Nullable Object item) {
        if (item instanceof Integer || item instanceof Long) {
            return ((Number) item).longValue();
        }
        if (item instanceof String text && KEY.matcher(text.strip()).matches()) {
            return Long.valueOf(text.strip());
        }
        return null;
    }

    private static Optional<FieldErrorItem> money(FormField field, Object value) {
        String key = field.key();
        if (!(value instanceof Map<?, ?> money)) return invalid(key, "error.field.money_invalid");
        Object amount = money.get("amount");
        Object currency = money.get("currency");
        String text = amount == null ? "" : String.valueOf(amount).strip();
        if (!DECIMAL.matcher(text).matches()) return invalid(key, "error.field.money_invalid");
        FieldParams params = field.params();
        if (currency == null || !params.currencies().contains(String.valueOf(currency))) {
            return error(
                    key,
                    EntityValidator.INVALID,
                    "error.field.currency_not_allowed",
                    Map.of("allowed", String.join(", ", params.currencies())));
        }
        BigDecimal number = new BigDecimal(text);
        int digits = Currency.getInstance(String.valueOf(currency)).getDefaultFractionDigits();
        if (digits >= 0 && number.stripTrailingZeros().scale() > digits) {
            return error(key, EntityValidator.INVALID, "error.field.scale_exceeded", Map.of("max", digits));
        }
        if ((field.min() != null && number.compareTo(field.min()) < 0)
                || (field.max() != null && number.compareTo(field.max()) > 0)) {
            return error(key, EntityValidator.OUT_OF_RANGE, "error.field.number_out_of_range", Map.of());
        }
        return scale(key, number, params.scale());
    }

    private static Optional<FieldErrorItem> keys(FormField field, Object value) {
        String key = field.key();
        Optional<List<Long>> keys = keyList(value);
        if (keys.isEmpty()) return invalid(key, "error.field.keys_invalid");
        Set<Long> distinct = new HashSet<>(keys.get());
        if (distinct.size() != keys.get().size()) return invalid(key, "error.field.keys_repeated");
        int most = field.params().maxItems() == null
                ? DEFAULT_MAX_ITEMS
                : field.params().maxItems();
        if (keys.get().size() > most) {
            return error(key, TOO_MANY, "error.field.too_many", Map.of("max", most));
        }
        return Optional.empty();
    }

    private static Optional<FieldErrorItem> json(FormField field, Object value) {
        String key = field.key();
        JsonNode node;
        try {
            node = value instanceof String text ? JSON.readTree(text) : JSON.valueToTree(value);
        } catch (JacksonException | IllegalArgumentException e) {
            return invalid(key, "error.field.json_invalid");
        }
        JsonRoot root = field.params().jsonRoot();
        boolean shaped = root == null
                ? node.isObject() || node.isArray()
                : root == JsonRoot.OBJECT ? node.isObject() : node.isArray();
        if (!shaped) return invalid(key, "error.field.json_invalid");
        if (node.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            return error(key, TOO_LARGE, "error.field.json_too_large", Map.of("max", MAX_JSON_BYTES / 1024));
        }
        return Optional.empty();
    }

    private static boolean isWebAddress(String text) {
        if (text.isEmpty() || text.length() > MAX_URL) return false;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static Optional<FieldErrorItem> invalid(String key, String messageKey) {
        return error(key, EntityValidator.INVALID, messageKey, Map.of());
    }

    private static Optional<FieldErrorItem> error(String key, String code, String messageKey, Map<String, ?> params) {
        return Optional.of(FieldErrorItem.keyed(key, code, messageKey, params));
    }
}
