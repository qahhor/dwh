package com.smartup24.cms.instance.studio.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.platform.api.entity.field.EntityFields.Builder;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The fields of a live form as the general runtime declares them (ADR-0038, ADR-0041): the system fields — name, code,
 * the record's number, the time of the last change and the record's org unit — and each declared field as a property of
 * the record's payload of its type.
 */
final class StudioFields {
    private StudioFields() {}

    /**
     * The record's org unit: its author's home unit by default (ADR-0041, owner decision 1). A user with the update right
     * moves the record to another unit; the runtime keeps both the old and the new unit within the mover's scope, and
     * the picker offers the units of that scope.
     */
    static Builder orgUnit() {
        return ref(
                        StudioEntityCompiler.ORG_UNIT,
                        "studio.record.org_unit",
                        QueryRef.whole(StudioEntityCompiler.ORG_UNITS, "name"))
                .column("org_unit_id")
                .defaultValue(FieldDefault.currentOrgUnit())
                .list(hidden());
    }

    static Builder name() {
        return text("name", "studio.record.name")
                .column("name")
                .required()
                .length(1, 255)
                .list(sortable().searchable());
    }

    static Builder code() {
        return text("code", "studio.record.code")
                .column("code")
                .required()
                .length(1, 64)
                .matching("[a-z0-9_-]+")
                .list(sortable().searchable());
    }

    static Builder modifiedAt() {
        return instant("modifiedAt", "studio.record.modified_at")
                .system(SystemColumn.MODIFIED_AT)
                .list(sortable());
    }

    /**
     * The record's number in its form, given by the database in turn (V224) and only read; a record saved before V224
     * has none, so the number is listed but not sorted.
     */
    static Builder recordNumber() {
        return number(StudioFormLayout.RECORD_NUMBER, "studio.record.number")
                .column("record_number")
                .readonly();
    }

    static Builder field(Field field, String prefix, boolean title, Function<String, String> titles) {
        String key = field.id();
        String label = prefix + ".field." + key;
        Builder builder = switch (field.type()) {
            case "text" -> text(key, label);
            case "textarea" -> textarea(key, label);
            case "number" -> number(key, label);
            case "date" -> date(key, label);
            case "datetime" -> instant(key, label);
            case "time" -> time(key, label);
            case "bool" -> bool(key, label);
            case "email" -> email(key, label);
            case "phone" -> phone(key, label);
            case "url" -> url(key, label);
            case "select" -> select(key, label, codes(field), optionPrefix(prefix, key));
            case "user" -> ref(key, label).target(StudioEntityCompiler.USERS, "name");
            case "ref" -> reference(key, label, field, titles);
            default -> throw new IllegalArgumentException("Unsupported live field type");
        };
        builder.jsonProperty("payload", key);
        if (field.required()) builder.required();
        if (field.maxLength() != null) builder.length(null, field.maxLength());
        if (field.scale() != null) builder.scale(field.scale());
        if (!field.listed()) builder.formOnly();
        else if (title) builder.list(searchable());
        if (field.defaultValue() != null) builder.defaultValue(FieldDefault.fixed(field.defaultValue()));
        return builder;
    }

    /** A reference to a list is an enumeration of it; one to a live form keeps the key of its record. */
    private static Builder reference(String key, String label, Field field, Function<String, String> titles) {
        Target target = Objects.requireNonNull(field.target(), "A live reference names its target");
        return "list".equals(target.kind())
                ? enumeration(key, label, StudioListEnums.reference(target.code()))
                : ref(key, label).target(target.code(), titles.apply(target.code()));
    }

    private static List<String> codes(Field field) {
        return Objects.requireNonNull(field.options(), "A live select has its choices").stream()
                .map(Option::code)
                .toList();
    }

    static String optionPrefix(String prefix, String key) {
        return prefix + ".option." + key + ".";
    }
}
