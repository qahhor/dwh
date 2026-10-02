package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Step 6 of ADR-0032, 6.3: the body of a save read field by field against the declaration (ADR-0032, 6.2 and 12, "mass
 * assignment"). Only the written fields of the form are taken — a system property ({@code id}, {@code revision},
 * {@code createdBy}…), {@code labels}, {@code actions}, an undeclared key, a field written by the server only (an
 * expression, a computed value, a system column) is {@code unknown_field}; a value of the wrong JSON type is
 * {@code invalid}. The custom field values come as the object {@code attributes}; a declared attribute field may come
 * under its own key or in {@code attributes}. The rows of a collection come as an array of objects under its key
 * (ADR-0032, 9.1): a row takes its {@code id} and the written fields of a row, each problem addressed
 * {@code lines[3].qty} by the row's place in the body.
 */
public final class EntityRequestReader {

    /** The field error of a value whose JSON type the field never takes. */
    public static final String WRONG_TYPE_KEY = "error.field.value_type";

    private EntityRequestReader() {}

    /**
     * What the body says.
     *
     * @param values      the declared fields the body writes, by key, as Java values
     * @param attributes  the custom field values, or null when the body leaves them as they are
     * @param errors      the properties refused, each addressed to its key
     * @param collections the rows of each collection the body sends, by the collection's key, as Java values
     */
    public record Body(
            Map<String, @Nullable Object> values,
            @Nullable Map<String, Object> attributes,
            List<FieldErrorItem> errors,
            Map<String, List<Map<String, @Nullable Object>>> collections) {
        public Body {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            errors = List.copyOf(errors);
            collections = Collections.unmodifiableMap(new LinkedHashMap<>(collections));
        }
    }

    /** Reads the body of a create or an update of {@code entity}: a JSON object, else 400. */
    public static Body read(EntityDefinition entity, @Nullable JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.common.entity_body_invalid");
        }
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        Map<String, Object> attributes = null;
        List<FieldErrorItem> errors = new ArrayList<>();
        Map<String, List<Map<String, @Nullable Object>>> collections = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : body.properties()) {
            String key = property.getKey();
            JsonNode node = property.getValue();
            Optional<EntityCollection> collection = model.collection(key);
            if (collection.isPresent()) {
                if (!node.isArray()) {
                    errors.add(wrongType(key));
                } else {
                    collections.put(key, rows(collection.get(), node, errors));
                }
                continue;
            }
            if (EntityModel.ATTRIBUTES.equals(key)) {
                if (node.isNull()) continue;
                if (!node.isObject()) {
                    errors.add(wrongType(key));
                    continue;
                }
                attributes = attributes == null ? new LinkedHashMap<>() : attributes;
                for (Map.Entry<String, Object> value : objectOf(node).entrySet()) {
                    if (takesAttribute(entity, model, value.getKey())) {
                        attributes.put(value.getKey(), value.getValue());
                    } else {
                        errors.add(FieldErrorItem.keyed(
                                EntityModel.ATTRIBUTES + "." + value.getKey(),
                                EntityFieldRights.UNKNOWN_FIELD,
                                "error.field.unknown"));
                    }
                }
                continue;
            }
            Optional<EntityField> field = model.field(key).filter(EntityRequestReader::written);
            if (field.isEmpty()) {
                errors.add(FieldErrorItem.keyed(key, EntityFieldRights.UNKNOWN_FIELD, "error.field.unknown"));
            } else if (!takes(field.get(), node)) {
                errors.add(wrongType(key));
            } else if (field.get().source() instanceof FieldSource.Attribute attribute) {
                attributes = attributes == null ? new LinkedHashMap<>() : attributes;
                attributes.put(attribute.code(), valueOf(node));
            } else {
                values.put(key, valueOf(node));
            }
        }
        return new Body(values, attributes, errors, collections);
    }

    /**
     * The rows of a collection as sent: each an object of its {@code id} — a whole number, absent for a new row — and
     * the written fields of a row; anything else is refused at {@code <collection>[<i>].<key>}.
     */
    private static List<Map<String, @Nullable Object>> rows(
            EntityCollection collection, JsonNode array, List<FieldErrorItem> errors) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode node : array.values()) {
            String at = collection.key() + "[" + index++ + "]";
            Map<String, @Nullable Object> row = new LinkedHashMap<>();
            rows.add(row);
            if (!node.isObject()) {
                errors.add(wrongType(at));
                continue;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String key = property.getKey();
                JsonNode value = property.getValue();
                if (EntityCollection.ID.equals(key)) {
                    if (value.isNull()) continue;
                    if (value.isIntegralNumber() && value.canConvertToLong()) {
                        row.put(key, value.asLong());
                    } else {
                        errors.add(wrongType(at + "." + key));
                    }
                    continue;
                }
                Optional<EntityField> field = collection
                        .field(key)
                        .filter(declared -> declared.source().writable());
                if (field.isEmpty()) {
                    errors.add(FieldErrorItem.keyed(
                            at + "." + key, EntityFieldRights.UNKNOWN_FIELD, "error.field.unknown"));
                } else if (!takes(field.get(), value)) {
                    errors.add(wrongType(at + "." + key));
                } else {
                    row.put(key, valueOf(value));
                }
            }
        }
        return rows;
    }

    /** The parameters of a record action: a JSON object, else 400; an empty body is no parameters. */
    public static Map<String, Object> params(@Nullable JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) return Map.of();
        if (!body.isObject()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.common.entity_body_invalid");
        }
        return objectOf(body);
    }

    /**
     * Whether a value in {@code attributes} has a place: any code of an entity with the administrator's custom fields
     * (their definitions check them), otherwise only the code of a declared attribute field — an entity without either
     * keeps no free values (ADR-0032, 12, "mass assignment").
     */
    private static boolean takesAttribute(EntityDefinition entity, EntityModel model, String code) {
        if (entity.customEntity() != null) return true;
        return model.fields().stream()
                .anyMatch(field -> written(field)
                        && field.source() instanceof FieldSource.Attribute attribute
                        && attribute.code().equals(code));
    }

    /** A field a save writes: a column, an attribute, money columns or a link table on the form. */
    private static boolean written(EntityField field) {
        return field.form() != null && field.source().writable();
    }

    /** Whether the field takes a value of this JSON type; null clears any field. */
    private static boolean takes(EntityField field, JsonNode node) {
        if (node.isNull()) return true;
        return switch (field.type()) {
            case NUMBER -> node.isNumber() || node.isString();
            case BOOLEAN -> node.isBoolean();
            case REF -> node.isIntegralNumber() || node.isString();
            case MULTI_REF -> node.isArray();
            // Money in the document's currency may come as its amount alone (ADR-0032, 9.1).
            case MONEY ->
                node.isObject() || (field.options().currencyFrom() != null && (node.isNumber() || node.isString()));
            case FILE, IMAGE -> node.isString() || node.isObject();
            case JSON -> true;
            default -> node.isString();
        };
    }

    private static FieldErrorItem wrongType(String key) {
        return FieldErrorItem.keyed(key, EntityValidator.INVALID, WRONG_TYPE_KEY);
    }

    private static Map<String, Object> objectOf(JsonNode node) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            object.put(property.getKey(), valueOf(property.getValue()));
        }
        return object;
    }

    /** A JSON value as Java: text, a whole number as long, a fraction exactly, a flag, a map, a list, or null. */
    static @Nullable Object valueOf(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.asLong();
        if (node.isNumber()) return node.decimalValue();
        if (node.isArray()) {
            List<@Nullable Object> items = new ArrayList<>();
            node.values().forEach(item -> items.add(valueOf(item)));
            return items;
        }
        return objectOf(node);
    }
}
