package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * How each field of an entity is selected into a row of its list (ADR-0032, 3.4 and 4.1), under the record's key:
 * a value as it is filtered, money as its amount and its currency ({@code "total"}, {@code "totalCurrency"}), a file as
 * its id, name, size and type read from the published view of the files module ({@code mf_pub_files}, ADR-0026), JSON
 * as its text and several references as their keys. {@link EntityRowMapper} reads the row back into the record's
 * values.
 */
public final class EntitySelect {

    private EntitySelect() {}

    /**
     * The select columns of one field ({@code <sql> as "<key>"}), none for a system column (selected by the list).
     *
     * @param fieldSql the SQL of another field by its key: the currency of money that takes it from a field
     */
    public static List<String> columns(EntityField field, String alias, Function<String, String> fieldSql) {
        FieldSource source = field.source();
        if (source instanceof FieldSource.SystemValue) return List.of();
        String key = field.key();
        if (field.type() == FieldType.MONEY) {
            return List.of(
                    as(field.sql(alias), key),
                    as(field.currencySql(alias, fieldSql), key + EntityField.CURRENCY_SUFFIX));
        }
        return List.of(as(read(field, alias), key));
    }

    private static String read(EntityField field, String alias) {
        FieldType type = field.type();
        if (type == FieldType.FILE || type == FieldType.IMAGE) {
            return "(select json_build_object('id', f.id, 'name', f.original_name, 'size', f.size_bytes,"
                    + " 'contentType', f.mime_type)::text from mf_pub_files f where f.id = " + field.sql(alias) + ")";
        }
        if (type == FieldType.JSON) {
            return field.sql(alias) + "::text";
        }
        return field.sql(alias);
    }

    private static String as(String sql, String key) {
        return Objects.requireNonNull(sql, key) + " as \"" + key + "\"";
    }
}
