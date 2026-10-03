package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.ListPart;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The list fields of an entity, derived from its declaration (ADR-0032, 3.4): the field registry's own types stay in
 * the platform, so the declaration API (ADR-0033, 3.2) names only the keys the list will have
 * ({@link EntityField#listKeys()}).
 */
public final class EntityListFields {

    private EntityListFields() {}

    /**
     * The list type a field of {@code type} is shown, filtered and exported as: text kinds as text, a moment as an
     * instant, a select and an enumeration as an enumeration, a reference as the number key of the row it names, money
     * as its amount, several references as a set of keys, a file and JSON as a value that is only there or not.
     */
    public static QueryFieldType listType(FieldType type) {
        return switch (type) {
            case TEXT, TEXTAREA, MARKDOWN, EMAIL, PHONE, URL -> QueryFieldType.TEXT;
            case NUMBER, REF, MONEY -> QueryFieldType.NUMBER;
            case DATE -> QueryFieldType.DATE;
            case DATETIME -> QueryFieldType.INSTANT;
            case TIME -> QueryFieldType.TIME;
            case BOOLEAN -> QueryFieldType.BOOLEAN;
            case SELECT, ENUM -> QueryFieldType.ENUM;
            case MULTI_REF -> QueryFieldType.REF_SET;
            case FILE, IMAGE, JSON -> QueryFieldType.OBJECT;
        };
    }

    /** The fields of the model's list, in declaration order, read over its alias. */
    public static List<QueryField> listFields(EntityModel model) {
        return model.fields().stream()
                .flatMap(field -> queryFields(field, model.alias(), model::sqlOf).stream())
                .toList();
    }

    /**
     * The list field of {@code field}, its value read over the entity's table aliased {@code alias}, or null when the
     * field is only on the form.
     */
    public static @Nullable QueryField queryField(EntityField field, String alias) {
        ListPart list = field.list();
        if (list == null) return null;
        QueryFieldType listType = listType(field.type());
        boolean enumeration = listType == QueryFieldType.ENUM;
        return new QueryField(
                field.key(),
                field.labelKey(),
                listType,
                field.sql(alias),
                list.isFilterable(),
                list.isSortable(),
                list.isNullable() || !listType.sortable() || field.source() instanceof FieldSource.Attribute,
                list.isDefaultVisible(),
                enumeration ? field.options().options() : List.of(),
                enumeration ? field.options().optionLabelPrefix() : null,
                list.isSearchable(),
                field.access().requiredForm(),
                field.access().requiredAction(),
                field.label(),
                field.attribute(),
                field.options().ref(),
                field.type().formatted() ? field.type().wire() : null,
                null);
    }

    /**
     * The list fields of {@code field}: the field itself and, for money, the hidden field that filters it by its
     * currency ({@code totalCurrency}, an enumeration of its currencies; ADR-0032, 4.1). Empty when the field is only
     * on the form.
     *
     * @param fieldSql the SQL of another field of the record by its key: the currency of money that takes it from a
     *                 field
     */
    public static List<QueryField> queryFields(EntityField field, String alias, Function<String, String> fieldSql) {
        QueryField listed = queryField(field, alias);
        if (listed == null) return List.of();
        List<QueryField> fields = new ArrayList<>();
        fields.add(listed);
        if (field.type() == FieldType.MONEY) {
            fields.add(new QueryField(
                    field.key() + EntityField.CURRENCY_SUFFIX,
                    field.labelKey(),
                    QueryFieldType.ENUM,
                    field.currencySql(alias, fieldSql),
                    listed.filterable(),
                    false,
                    false,
                    false,
                    field.options().currencies(),
                    null,
                    false,
                    listed.requiredForm(),
                    listed.requiredAction(),
                    field.label(),
                    null,
                    null,
                    EntityField.CURRENCY_FORMAT,
                    null));
        }
        return fields;
    }

    /** The list fields of {@code field}, whose money keeps its currency itself. */
    public static List<QueryField> queryFields(EntityField field, String alias) {
        return queryFields(field, alias, key -> {
            throw new IllegalStateException("Entity field " + field.key() + " takes its currency from " + key);
        });
    }
}
