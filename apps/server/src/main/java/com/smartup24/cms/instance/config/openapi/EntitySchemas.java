package com.smartup24.cms.instance.config.openapi;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import com.smartup24.cms.platform.api.entity.field.FieldRules;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The JSON schemas of one entity, built from its fields (ADR-0032, 6.13): its record as the runtime answers it, the
 * body of a create and of a change, and a page of its list. A type becomes its JSON form — money and a file a schema
 * of their own, several references an array of keys, a select its options — {@code required} comes from the form,
 * {@code readOnly} from read-only and computed fields, and a field that needs a right is described too, marked
 * {@code x-requires}: the description does not depend on who reads it. The rows of a collection are an array of row
 * objects under its key (ADR-0032, 9.1): read with their {@code id} and {@code position}, written with the {@code id}
 * of a row to keep and without one for a new row.
 */
final class EntitySchemas {

    static final String MONEY = "EntityMoney";
    static final String FILE = "EntityFile";
    static final String ARCHIVED = "EntityArchivedRequest";

    private EntitySchemas() {}

    /** {@code ms.notes} → {@code MsNotes}: the stem of the entity's schema names and operation ids. */
    static String stem(String code) {
        StringBuilder stem = new StringBuilder();
        boolean upper = true;
        for (char letter : code.toCharArray()) {
            if (letter == '.' || letter == '_') {
                upper = true;
            } else {
                stem.append(upper ? Character.toUpperCase(letter) : letter);
                upper = false;
            }
        }
        return stem.toString();
    }

    /** The schemas every entity shares: money, a file, the body of the archive switch. */
    static Map<String, Schema<?>> shared() {
        Map<String, Schema<?>> schemas = new TreeMap<>();
        schemas.put(
                MONEY,
                new ObjectSchema()
                        .description("An amount, as text so no digit is lost, and its ISO 4217 currency")
                        .addProperty("amount", new StringSchema().example("1250.00"))
                        .addProperty("currency", new StringSchema().pattern("^[A-Z]{3}$"))
                        .required(List.of("amount", "currency")));
        schemas.put(
                FILE,
                new ObjectSchema()
                        .description("A stored file of a file or image field; a save sends its id")
                        .addProperty("id", new UUIDSchema())
                        .addProperty("name", new StringSchema())
                        .addProperty("size", new IntegerSchema().format("int64"))
                        .addProperty("contentType", new StringSchema()));
        schemas.put(
                ARCHIVED,
                new ObjectSchema()
                        .description("The state the archive switch sets")
                        .addProperty("archived", new BooleanSchema())
                        .required(List.of("archived")));
        return schemas;
    }

    /** The record as the runtime answers it (ADR-0032, 6.2). */
    static Schema<?> record(EntityDefinition entity) {
        ObjectSchema record = new ObjectSchema();
        record.description("A record of " + entity.code() + " as the viewer may read it");
        record.addProperty(SystemColumn.ID.key(), new IntegerSchema().format("int64"));
        record.addProperty(SystemColumn.REVISION.key(), new IntegerSchema().format("int64"));
        record.addProperty(SystemColumn.CREATED_AT.key(), new DateTimeSchema());
        record.addProperty(SystemColumn.CREATED_BY.key(), new IntegerSchema().format("int64"));
        record.addProperty(SystemColumn.MODIFIED_AT.key(), new DateTimeSchema());
        record.addProperty(SystemColumn.MODIFIED_BY.key(), new IntegerSchema().format("int64"));
        if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
            record.addProperty(EntityModel.ARCHIVED, new BooleanSchema());
            record.addProperty(EntityModel.ARCHIVED_AT, new DateTimeSchema());
        }
        for (EntityField field : model(entity).fields()) {
            if (field.source() instanceof FieldSource.SystemValue) continue;
            Schema<?> schema = value(field, true);
            FormField form = field.formField();
            if (form == null || form.computed() || form.flags().readonly() != null) {
                schema.readOnly(true);
            }
            record.addProperty(field.key(), marked(schema, field.access()));
        }
        for (EntityCollection collection : model(entity).collections()) {
            record.addProperty(collection.key(), rows(collection, true));
        }
        record.addProperty(EntityModel.ATTRIBUTES, attributes());
        record.addProperty(
                "actions",
                new ArraySchema().items(new StringSchema()).description("What the viewer may do with this record"));
        record.required(List.of(SystemColumn.ID.key(), SystemColumn.REVISION.key()));
        return record;
    }

    /**
     * The body of a create ({@code creating}) or of a change: the written fields of the form; a create requires the
     * required fields without a default.
     */
    static Schema<?> body(EntityDefinition entity, boolean creating) {
        ObjectSchema body = new ObjectSchema();
        body.description((creating ? "A new record of " : "The fields to change of a record of ") + entity.code()
                + "; any other property is refused (unknown_field)");
        body.additionalProperties(false);
        List<String> required = new ArrayList<>();
        for (EntityField field : model(entity).fields()) {
            FormField form = field.formField();
            if (form == null || !field.source().writable() || field.source() instanceof FieldSource.Attribute) {
                continue;
            }
            body.addProperty(field.key(), marked(value(field, false), field.access()));
            if (creating && form.required() && form.flags().defaultValue() == null) {
                required.add(field.key());
            }
        }
        for (EntityCollection collection : model(entity).collections()) {
            body.addProperty(collection.key(), rows(collection, false));
        }
        body.addProperty(EntityModel.ATTRIBUTES, attributes());
        if (!required.isEmpty()) body.required(required);
        return body;
    }

    /**
     * The rows of a collection: as read ({@code read}), each with its id, place and every field, a computed one read
     * only; as written, each with the id of a row to keep — none for a new row — and its written fields.
     */
    private static Schema<?> rows(EntityCollection collection, boolean read) {
        ObjectSchema row = new ObjectSchema();
        row.addProperty(EntityCollection.ID, new IntegerSchema().format("int64"));
        if (read) {
            row.addProperty(EntityCollection.POSITION, new IntegerSchema().format("int32"));
        } else {
            row.additionalProperties(false);
        }
        for (EntityField field : collection.fields()) {
            boolean written = field.source().writable();
            if (!read && !written) continue;
            Schema<?> schema = value(field, read);
            if (!written) schema.readOnly(true);
            row.addProperty(field.key(), schema);
        }
        return new ArraySchema()
                .items(row)
                .maxItems(collection.maxRows())
                .description("The rows of " + collection.key() + " in their order (ADR-0032, 9.1)");
    }

    /** A page of the entity's list (ADR-0016). */
    static Schema<?> page(String recordName) {
        return new ObjectSchema()
                .addProperty("items", new ArraySchema().items(ref(recordName)))
                .addProperty("nextCursor", new StringSchema())
                .addProperty("hasMore", new BooleanSchema())
                .addProperty("totalEstimated", new IntegerSchema().format("int64"))
                .addProperty("totalExact", new BooleanSchema());
    }

    static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    /** The JSON form of a field's value; a file reads as its facts and is written as its id. */
    private static Schema<?> value(EntityField field, boolean read) {
        Schema<?> schema = switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN, PHONE -> new StringSchema();
            case EMAIL -> new StringSchema().format("email");
            case URL -> new StringSchema().format("uri");
            case NUMBER -> new NumberSchema();
            case DATE -> new DateSchema();
            case DATETIME -> new DateTimeSchema();
            case TIME -> new StringSchema().pattern("^\\d{2}:\\d{2}(:\\d{2})?$");
            case BOOLEAN -> new BooleanSchema();
            case SELECT -> {
                StringSchema options = new StringSchema();
                field.options().options().forEach(options::addEnumItem);
                yield options;
            }
            case ENUM ->
                new StringSchema()
                        .description("The code of an item of " + field.options().enumeration());
            case REF -> new IntegerSchema().format("int64");
            case MULTI_REF -> new ArraySchema().items(new IntegerSchema().format("int64"));
            case MONEY -> ref(MONEY);
            case FILE, IMAGE -> read ? ref(FILE) : new UUIDSchema();
            case JSON -> new Schema<>().description("A JSON object or array, kept as it is");
        };
        FormField form = field.formField();
        if (form != null && schema.get$ref() == null) {
            FieldRules rules = Objects.requireNonNull(field.form()).rules();
            if (rules.minLength() != null) schema.minLength(rules.minLength());
            if (rules.maxLength() != null) schema.maxLength(rules.maxLength());
            if (rules.min() != null) schema.minimum(rules.min());
            if (rules.max() != null) schema.maximum(rules.max());
        }
        return schema;
    }

    /** A field that exists only for holders of a right is described with it ({@code x-requires: form.action}). */
    private static Schema<?> marked(Schema<?> schema, FieldAccess access) {
        if (access.restricted()) {
            schema.addExtension("x-requires", access.requiredForm() + "." + access.requiredAction());
        }
        return schema;
    }

    private static Schema<?> attributes() {
        return new MapSchema()
                .additionalProperties(new Schema<>())
                .description("The administrator's custom field values by code");
    }

    private static EntityModel model(EntityDefinition entity) {
        return Objects.requireNonNull(entity.model(), entity.code());
    }
}
