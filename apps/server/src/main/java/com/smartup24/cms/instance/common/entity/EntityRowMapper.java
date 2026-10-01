package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.json.JsonColumns;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a row of an entity's list ({@link EntityLists}, {@link EntitySelect}) into the record's values by key
 * (ADR-0032, 3.4 and 6.2): the system columns, each field by its type — money as {@code {"amount":"1250.00",
 * "currency":"UZS"}} with the amount as text, several references as their keys, a file as its id, name, size and type,
 * JSON as it is — and the custom field values under {@code attributes}. One mapper for every entity, so no module
 * writes its own.
 */
public final class EntityRowMapper implements RowMapper<Map<String, Object>> {

    private static final TypeReference<Object> ANY = new TypeReference<>() {};

    private final EntityModel model;
    private final JsonColumns json;

    public EntityRowMapper(EntityModel model, ObjectMapper mapper) {
        this.model = model;
        this.json = new JsonColumns(mapper, model.table());
    }

    @Override
    public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(SystemColumn.ID.key(), rs.getLong(SystemColumn.ID.key()));
        record.put(SystemColumn.REVISION.key(), rs.getLong(SystemColumn.REVISION.key()));
        putNotNull(record, SystemColumn.CREATED_AT.key(), instant(rs, SystemColumn.CREATED_AT.key()));
        putNotNull(record, SystemColumn.CREATED_BY.key(), rs.getObject(SystemColumn.CREATED_BY.key()));
        putNotNull(record, SystemColumn.MODIFIED_AT.key(), instant(rs, SystemColumn.MODIFIED_AT.key()));
        putNotNull(record, SystemColumn.MODIFIED_BY.key(), rs.getObject(SystemColumn.MODIFIED_BY.key()));
        for (EntityField field : model.fields()) {
            if (field.source() instanceof FieldSource.SystemValue) continue;
            putNotNull(record, field.key(), value(rs, field));
        }
        record.put(EntityModel.ATTRIBUTES, json.readObject(rs.getString(EntityModel.ATTRIBUTES)));
        return record;
    }

    private @Nullable Object value(ResultSet rs, EntityField field) throws SQLException {
        String key = field.key();
        return switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN, EMAIL, PHONE, URL, SELECT, ENUM -> rs.getString(key);
            case NUMBER -> rs.getBigDecimal(key);
            case REF -> {
                long id = rs.getLong(key);
                yield rs.wasNull() ? null : id;
            }
            case DATE -> {
                Date date = rs.getDate(key);
                yield date == null ? null : date.toLocalDate().toString();
            }
            case DATETIME -> instant(rs, key);
            case TIME -> {
                Time time = rs.getTime(key);
                yield time == null ? null : time.toLocalTime().toString();
            }
            case BOOLEAN -> {
                boolean flag = rs.getBoolean(key);
                yield rs.wasNull() ? null : flag;
            }
            case MONEY -> money(rs.getBigDecimal(key), rs.getString(key + EntityField.CURRENCY_SUFFIX));
            case MULTI_REF -> keys(rs.getArray(key));
            case FILE, IMAGE -> {
                String text = rs.getString(key);
                yield text == null ? null : json.readObject(text);
            }
            case JSON -> {
                String text = rs.getString(key);
                yield text == null ? null : json.read(text, ANY);
            }
        };
    }

    /**
     * Money as the API gives it (ADR-0032, 4.1): the amount as text, so no digit is lost on the way, with the digits of
     * its currency ({@code "1250.00"} in UZS, {@code "1250"} in JPY) unless it holds more.
     */
    static @Nullable Map<String, Object> money(@Nullable BigDecimal amount, @Nullable String currency) {
        if (amount == null) return null;
        int digits = currencyDigits(currency);
        BigDecimal shown = amount.setScale(
                Math.max(digits, Math.max(0, amount.stripTrailingZeros().scale())));
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", shown.toPlainString());
        money.put("currency", currency);
        return money;
    }

    private static int currencyDigits(@Nullable String currency) {
        return FieldValueRules.currencyDigits(currency, 0);
    }

    private static List<Long> keys(@Nullable Array array) throws SQLException {
        List<Long> keys = new ArrayList<>();
        if (array == null) return keys;
        for (Object key : (Object[]) array.getArray()) {
            keys.add(((Number) key).longValue());
        }
        return keys;
    }

    private static @Nullable String instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static void putNotNull(Map<String, Object> record, String key, @Nullable Object value) {
        if (value != null) {
            record.put(key, value);
        }
    }
}
