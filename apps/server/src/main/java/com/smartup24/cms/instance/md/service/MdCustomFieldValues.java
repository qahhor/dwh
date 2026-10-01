package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The form of custom field values: what a value of each type may look like and how it is stored. */
final class MdCustomFieldValues {

    /** The types whose values are text: equality on them is containment of a JSON string (plan 10/10, item 3.7). */
    private static final Set<String> TEXT_FIELD_TYPES = Set.of("string", "select");

    private MdCustomFieldValues() {}

    /**
     * The attributes with the number or boolean value of a string or select field written as a string; the same
     * map when nothing changes. Containment of {@code {"code": "123"}} does not match a stored {@code 123}.
     */
    static Map<String, Object> textValuesAsStrings(List<CustomFieldRecord> fields, Map<String, Object> attributes) {
        Map<String, Object> stored = null;
        for (var field : fields) {
            Object value = attributes.get(field.code());
            if (TEXT_FIELD_TYPES.contains(field.fieldType().toLowerCase(Locale.ROOT))
                    && (value instanceof Number || value instanceof Boolean)) {
                if (stored == null) {
                    stored = new LinkedHashMap<>(attributes);
                }
                stored.put(field.code(), value.toString());
            }
        }
        return stored == null ? attributes : stored;
    }

    static boolean isNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(value.toString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isBoolean(Object value) {
        return value instanceof Boolean
                || value.toString().equalsIgnoreCase("true")
                || value.toString().equalsIgnoreCase("false");
    }

    static boolean isDate(Object value) {
        try {
            LocalDate.parse(value.toString());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
