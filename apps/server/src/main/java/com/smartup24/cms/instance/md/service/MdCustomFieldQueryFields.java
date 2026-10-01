package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListExtender;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The custom fields of a list's entity as registry fields (ADR-0019, 2.3): a column, a filter and, for text,
 * the free search — read from the row's {@code attributes} at request time, so a field an administrator adds
 * appears without a release.
 *
 * <p>A value is cast only when it has the field's shape, so an old value written before validation existed
 * reads as empty instead of failing the whole page. Custom fields never sort: that needs an expression index,
 * and the application role cannot create indexes (least privilege, I-02).
 */
@Component
public class MdCustomFieldQueryFields implements QueryListExtender {

    /** The code rule the custom field service enforces; checked again because the code goes into SQL text. */
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private static final Pattern ATTRIBUTES_SQL = Pattern.compile("^[a-z_][a-z0-9_]*\\.attributes$");

    /** A moment as an ISO text with its offset; anything else reads as empty instead of failing the cast. */
    private static final String MOMENT = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3])"
            + ":[0-5][0-9](:[0-5][0-9]([.][0-9]{1,9})?)?(Z|[+-]([01][0-9]|2[0-3])(:?[0-5][0-9])?)$";

    private static final String TIME_OF_DAY = "^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$";

    private final MdCustomFieldService service;

    public MdCustomFieldQueryFields(MdCustomFieldService service) {
        this.service = service;
    }

    /**
     * Read through the service's cluster cache of definitions (ADR-0025, plan 10/10, item 5.0): a change of a custom
     * field clears it on every node, so {@code query-meta} and the list show a new field at once.
     */
    @Override
    public List<QueryField> extraFields(QueryList list) {
        if (list.customEntity() == null
                || list.attributesSql() == null
                || !ATTRIBUTES_SQL.matcher(list.attributesSql()).matches()) {
            return List.of();
        }
        List<QueryField> fields = new ArrayList<>();
        for (CustomFieldRecord record : service.getFields(list.customEntity())) {
            toField(record, list.attributesSql()).ifPresent(fields::add);
        }
        return fields;
    }

    /** The registry key of a custom field: {@code cf} and its code in camel case ({@code region_code} → {@code cfRegionCode}). */
    public static String key(String code) {
        StringBuilder key = new StringBuilder("cf");
        boolean upper = true;
        for (char c : code.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                key.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return key.toString();
    }

    private Optional<QueryField> toField(CustomFieldRecord record, String attributes) {
        String code = record.code();
        String key = key(code);
        if (code == null || !CODE.matcher(code).matches() || key.length() > 64) {
            return Optional.empty();
        }
        String raw = "(" + attributes + "->>'" + code + "')";
        String type = record.fieldType().toLowerCase(Locale.ROOT);
        if ("select".equals(type)) {
            List<String> options = service.parseSelectOptions(record.optionsJson());
            return Optional.of(
                    options.isEmpty()
                            ? QueryField.custom(key, record.name(), QueryFieldType.TEXT, raw, code, List.of())
                            : QueryField.custom(key, record.name(), QueryFieldType.ENUM, raw, code, options));
        }
        return shape(type, raw).map(shape -> {
            QueryField field = QueryField.custom(key, record.name(), shape.type(), shape.sql(), code, List.of());
            return "user_ref".equals(type) ? field.refersTo(QueryRef.paged("/iam/users", "name")) : field;
        });
    }

    /** How a custom field of one type reads as a registry field: its type and the expression over its raw text. */
    private record Shape(QueryFieldType type, String sql) {}

    private static Optional<Shape> shape(String type, String raw) {
        return Optional.ofNullable(
                switch (type) {
                    case "string" -> new Shape(QueryFieldType.TEXT, raw);
                    case "number" ->
                        new Shape(
                                QueryFieldType.NUMBER, cast(raw, "^-?[0-9]{1,15}([.][0-9]{1,6})?$", raw + "::numeric"));
                    case "user_ref" -> new Shape(QueryFieldType.NUMBER, cast(raw, "^[0-9]{1,18}$", raw + "::bigint"));
                    case "date" ->
                        new Shape(
                                QueryFieldType.DATE,
                                cast(raw, "^[0-9]{4}-[0-9]{2}-[0-9]{2}", "left(" + raw + ", 10)::date"));
                    case "datetime" -> new Shape(QueryFieldType.INSTANT, cast(raw, MOMENT, raw + "::timestamptz"));
                    case "time" -> new Shape(QueryFieldType.TIME, cast(raw, TIME_OF_DAY, raw + "::time"));
                    case "boolean" ->
                        new Shape(
                                QueryFieldType.BOOLEAN,
                                "(case when lower(" + raw + ") in ('true', 'false') then lower(" + raw
                                        + ")::boolean end)");
                    default -> null;
                });
    }

    /** The cast only of a value of the right shape; any other value is empty, not an error for the whole page. */
    private static String cast(String raw, String shape, String typed) {
        return "(case when " + raw + " ~ '" + shape + "' then " + typed + " end)";
    }
}
