package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityValidator;
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
 * under its own key or in {@code attributes}.
 */
public final class EntityRequestReader {

    /** The field error of a value whose JSON type the field never takes. */
    public static final String WRONG_TYPE_KEY = "error.field.value_type";

    private EntityRequestReader() {}

    /**
     * What the body says.
     *
     * @param values     the declared fields the body writes, by key, as Java values
     * @param attributes the custom field values, or null when the body leaves them as they are
     * @param errors     the properties refused, each addressed to its key
     */
    public record Body(
            Map<String, @Nullable Object> values,
            @Nullable Map<String, Object> attributes,
            List<FieldErrorItem> errors) {
        public Body {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            errors = List.copyOf(errors);
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
        for (Map.Entry<String, JsonNode> property : body.properties()) {
            String key = property.getKey();
            JsonNode node = property.getValue();
            if (EntityModel.ATTRIBUTES.equals(key)) {
                if (node.isNull()) continue;
                if (!node.isObject()) {
                    errors.add(wrongType(key));
                    continue;
                }
                attributes = attributes == null ? new LinkedHashMap<>() : attributes;
                attributes.putAll(objectOf(node));
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
        return new Body(values, attributes, errors);
    }

    /** The parameters of a record action: a JSON object, else 400; an empty body is no parameters. */
    public static Map<String, Object> params(@Nullable JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) return Map.of();
        if (!body.isObject()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.common.entity_body_invalid");
        }
        return objectOf(body);
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
            case MONEY -> node.isObject();
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
