package com.smartup24.cms.instance.common.entity.store;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.json.JsonColumns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What one save writes, by the declaration (ADR-0032, 4.1 and 6.3, step 10): the columns of the entity's table with
 * their values as JDBC takes them — a number exactly, a date, a moment and a time parsed, money as its amount and its
 * currency, a file as its id, JSON as text cast to {@code jsonb} — the keys of each link table and the files of each
 * file field. The names come from the declaration only; the values are parameters.
 *
 * @param columns the column assignments, in declaration order
 * @param links   the keys of every several-references field the save writes, by its link table
 * @param files   the file of every file field the save writes, by field key; null removes it
 */
public record EntityWrite(
        List<Column> columns, Map<FieldSource.Link, List<Long>> links, Map<String, @Nullable UUID> files) {

    /** One column and its value; {@code cast} wraps the parameter ({@code cast(:p0 as jsonb)}) or is null. */
    public record Column(
            String name, @Nullable Object value, @Nullable String cast) {
        public String parameter(String name) {
            return cast == null ? ":" + name : "cast(:" + name + " as " + cast + ")";
        }
    }

    /**
     * The write of the prepared values of a save.
     *
     * @param values the values by field key, as the field rules left them (ADR-0032, 6.3, steps 6–9)
     * @param json   the JSON of the entity's table
     */
    public static EntityWrite of(EntityDefinition entity, Map<String, ?> values, JsonColumns json) {
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        List<Column> columns = new ArrayList<>();
        Map<FieldSource.Link, List<Long>> links = new LinkedHashMap<>();
        Map<String, @Nullable UUID> files = new LinkedHashMap<>();
        for (EntityField field : model.fields()) {
            if (!values.containsKey(field.key())) continue;
            Object value = values.get(field.key());
            switch (field.source()) {
                case FieldSource.Column column -> {
                    columns.add(new Column(column.name(), columnValue(field, value, json), cast(field)));
                    if ((field.type() == FieldType.FILE || field.type() == FieldType.IMAGE)) {
                        files.put(field.key(), value == null ? null : uuid(value));
                    }
                }
                case FieldSource.MoneyColumns money -> {
                    Map<?, ?> amount = value instanceof Map<?, ?> map ? map : Map.of();
                    columns.add(new Column(money.amount(), decimal(amount.get("amount")), null));
                    if (money.currency() != null) {
                        Object currency = amount.get("currency");
                        columns.add(new Column(money.currency(), currency == null ? null : currency.toString(), null));
                    }
                }
                case FieldSource.Link link -> links.put(link, keys(value));
                default -> {
                    // An attribute is written with the record's attributes; the rest is never written.
                }
            }
        }
        return new EntityWrite(columns, links, files);
    }

    /** The owner column of a personal record (ADR-0032, 5.1) when it is not the author's {@code created_by}. */
    public static @Nullable String ownerColumn(EntityModel model) {
        if (model.scope() instanceof EntityScope.Owner owner && !"created_by".equals(owner.ownerColumn())) {
            return owner.ownerColumn();
        }
        return null;
    }

    public EntityWrite {
        columns = List.copyOf(columns);
        links = Collections.unmodifiableMap(new LinkedHashMap<>(links));
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    private static @Nullable Object columnValue(EntityField field, @Nullable Object value, JsonColumns json) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return switch (field.type()) {
            case NUMBER -> decimal(value);
            case DATE -> text.isEmpty() ? null : LocalDate.parse(text);
            case DATETIME -> text.isEmpty() ? null : OffsetDateTime.parse(text);
            case TIME -> text.isEmpty() ? null : LocalTime.parse(text);
            case BOOLEAN -> value instanceof Boolean flag ? flag : Boolean.valueOf(text);
            case REF -> value instanceof Number number ? (Object) number.longValue() : refKey(text);
            case FILE, IMAGE -> uuid(value);
            case JSON -> json.write(value);
            default -> value instanceof String string ? string : String.valueOf(value);
        };
    }

    private static @Nullable String cast(EntityField field) {
        return field.type() == FieldType.JSON ? "jsonb" : null;
    }

    /** A reference by its key, or by a code for a column that keeps codes. */
    private static Object refKey(String text) {
        return text.chars().allMatch(Character::isDigit) && !text.isEmpty() ? (Object) Long.valueOf(text) : text;
    }

    private static @Nullable BigDecimal decimal(@Nullable Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return value instanceof BigDecimal number
                ? number
                : new BigDecimal(String.valueOf(value).strip());
    }

    private static UUID uuid(Object value) {
        if (value instanceof Map<?, ?> file) return UUID.fromString(String.valueOf(file.get("id")));
        return UUID.fromString(String.valueOf(value).strip());
    }

    private static List<Long> keys(@Nullable Object value) {
        List<Long> keys = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object key : list) {
                keys.add(key instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(key)));
            }
        }
        return keys;
    }
}
