package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.common.query.QueryListExtender;
import com.greenwhite.dwh.instance.md.repository.MdCustomFieldRepository;
import com.greenwhite.dwh.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private final MdCustomFieldRepository repository;
    private final MdCustomFieldService service;

    public MdCustomFieldQueryFields(MdCustomFieldRepository repository, MdCustomFieldService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public List<QueryField> extraFields(QueryList list) {
        if (list.customEntity() == null || list.attributesSql() == null
                || !ATTRIBUTES_SQL.matcher(list.attributesSql()).matches()) {
            return List.of();
        }
        List<QueryField> fields = new ArrayList<>();
        for (CustomFieldRecord record : repository.findByEntityType(list.customEntity())) {
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
        return Optional.ofNullable(switch (record.fieldType().toLowerCase(Locale.ROOT)) {
            case "string" -> QueryField.custom(key, record.name(), QueryFieldType.TEXT, raw, code, List.of());
            case "number" -> QueryField.custom(key, record.name(), QueryFieldType.NUMBER,
                    "(case when " + raw + " ~ '^-?[0-9]{1,15}([.][0-9]{1,6})?$' then " + raw + "::numeric end)",
                    code, List.of());
            case "user_ref" -> QueryField.custom(key, record.name(), QueryFieldType.NUMBER,
                    "(case when " + raw + " ~ '^[0-9]{1,18}$' then " + raw + "::bigint end)", code, List.of());
            case "date" -> QueryField.custom(key, record.name(), QueryFieldType.DATE,
                    "(case when " + raw + " ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' then left(" + raw + ", 10)::date end)",
                    code, List.of());
            case "boolean" -> QueryField.custom(key, record.name(), QueryFieldType.BOOLEAN,
                    "(case when lower(" + raw + ") in ('true', 'false') then lower(" + raw + ")::boolean end)",
                    code, List.of());
            case "select" -> {
                List<String> options = service.parseSelectOptions(record.optionsJson());
                yield options.isEmpty()
                        ? QueryField.custom(key, record.name(), QueryFieldType.TEXT, raw, code, List.of())
                        : QueryField.custom(key, record.name(), QueryFieldType.ENUM, raw, code, options);
            }
            default -> null;
        });
    }
}
