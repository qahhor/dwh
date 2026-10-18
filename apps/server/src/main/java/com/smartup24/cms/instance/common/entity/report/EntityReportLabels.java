package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.entity.EntityTexts;
import com.smartup24.cms.instance.common.entity.runtime.EntityLabelResolver;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Names the group values of a report (ADR-0036, plan 10/10, the form and report builder): a choice and an item of a
 * reference list by its label, a user and a record by the name its entity gives it — read in the viewer's scope, in one
 * statement per grouping — in the language of the work ({@link RequestLanguage}). A date, a yes/no, a currency and an
 * empty group keep no word; a record the viewer may not see is not named. A report ordered by its words is ordered here,
 * once they are read, and then cut to its top.
 */
@Component
public class EntityReportLabels {
    private final EntityLabelResolver resolver;
    private final ObjectProvider<EntityTexts> texts;

    public EntityReportLabels(EntityLabelResolver resolver, ObjectProvider<EntityTexts> texts) {
        this.resolver = resolver;
        this.texts = texts;
    }

    /** The result with the words of its group values, in the report's order and within its top. */
    public QueryAggregateResult label(QueryAggregate aggregate, QueryAggregateResult raw) {
        List<Group> groups = aggregate.groups();
        List<Map<Object, String>> words = new ArrayList<>();
        Map<String, String> dictionary = null;
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            QueryField field = group.field();
            Set<Object> values = values(raw, index);
            if (group.trunc() != null || group.implicit() || values.isEmpty()) {
                words.add(Map.of());
            } else if (field.type() == QueryFieldType.ENUM) {
                if (field.enumLabels() == null && dictionary == null) dictionary = dictionary();
                words.add(choices(field, values, dictionary));
            } else if (field.type() == QueryFieldType.NUMBER && field.ref() != null) {
                words.add(records(field.ref(), values));
            } else {
                words.add(Map.of());
            }
        }
        List<AggregateRow> rows = new ArrayList<>();
        for (AggregateRow row : raw.rows()) {
            List<@Nullable String> labels = new ArrayList<>();
            for (int index = 0; index < row.groups().size(); index++) {
                Object value = row.groups().get(index);
                labels.add(value == null ? null : words.get(index).get(value));
            }
            rows.add(new AggregateRow(row.groups(), row.values(), labels));
        }
        QueryAggregate.Order order = aggregate.order();
        boolean truncated = raw.truncated();
        if (order.by() == QueryAggregate.By.LABEL) {
            rows.sort(byWords(order.descending()));
            Integer limit = order.limit();
            if (limit != null && rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
                truncated = true;
            }
        }
        return new QueryAggregateResult(raw.groups(), raw.measures(), rows, truncated);
    }

    private static Set<Object> values(QueryAggregateResult raw, int index) {
        Set<Object> values = new LinkedHashSet<>();
        for (AggregateRow row : raw.rows()) {
            Object value = row.groups().get(index);
            if (value != null) values.add(value);
        }
        return values;
    }

    /** The labels of the choices: read with the list's items, else from the dictionary by the choice's key. */
    private static Map<Object, String> choices(
            QueryField field, Set<Object> values, @Nullable Map<String, String> dictionary) {
        Map<Object, String> words = new HashMap<>();
        Map<String, String> items = field.enumLabels();
        String prefix = field.enumLabelPrefix();
        for (Object value : values) {
            String code = String.valueOf(value);
            String word = items != null
                    ? items.get(code)
                    : prefix != null && dictionary != null ? dictionary.get(prefix + code) : null;
            if (word != null) words.put(value, word);
        }
        return words;
    }

    private Map<Object, String> records(QueryRef ref, Set<Object> values) {
        List<Long> keys = values.stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue())
                .toList();
        Map<Long, String> names = resolver.labels(ref, keys, EntityReads.userId());
        Map<Object, String> words = new HashMap<>();
        for (Object value : values) {
            if (value instanceof Number number && names.containsKey(number.longValue())) {
                words.put(value, names.get(number.longValue()));
            }
        }
        return words;
    }

    private Map<String, String> dictionary() {
        EntityTexts source = texts.getIfAvailable();
        return source == null ? Map.of() : source.dictionary(RequestLanguage.current());
    }

    /** Rows by the words of their groups, column after column, in the language's order; empty groups last. */
    private static Comparator<AggregateRow> byWords(boolean descending) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag(RequestLanguage.current()));
        collator.setStrength(Collator.SECONDARY);
        return (left, right) -> {
            for (int index = 0; index < left.groups().size(); index++) {
                String a = word(left, index);
                String b = word(right, index);
                if (a == null && b == null) continue;
                if (a == null) return 1;
                if (b == null) return -1;
                int compared = collator.compare(a, b);
                if (compared != 0) return descending ? -compared : compared;
            }
            return 0;
        };
    }

    private static @Nullable String word(AggregateRow row, int index) {
        String label = row.labels().get(index);
        if (label != null) return label;
        Object value = row.groups().get(index);
        return value == null ? null : String.valueOf(value);
    }
}
