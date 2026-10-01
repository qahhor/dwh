package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.entity.field.FormPart;
import com.smartup24.cms.instance.common.query.QueryField;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The rules across an entity's fields (ADR-0032, 4.4): a condition looks at a form field of the entity that holds one
 * value picked from known ones (select, enumeration, yes/no, reference), and the list fields derived from the fields
 * (the hidden currency of money) take keys no other field uses.
 */
final class EntityModelRules {

    private EntityModelRules() {}

    static void check(String table, List<EntityField> fields) {
        Map<String, EntityField> byKey =
                fields.stream().collect(Collectors.toMap(EntityField::key, Function.identity(), (a, b) -> a));
        for (EntityField field : fields) {
            FormPart form = field.form();
            if (form == null) continue;
            checkCondition(table, field, form.visibleWhen(), byKey);
            checkCondition(
                    table,
                    field,
                    form.readonly() == null ? null : form.readonly().when(),
                    byKey);
        }
        Set<String> listKeys = new HashSet<>();
        for (EntityField field : fields) {
            for (QueryField listed : field.queryFields("t")) {
                if (!listKeys.add(listed.key())
                        || (!listed.key().equals(field.key()) && byKey.containsKey(listed.key()))) {
                    throw new IllegalArgumentException("Table " + table + ": the list field " + listed.key() + " of "
                            + field.key() + " takes the key of another field");
                }
            }
        }
    }

    private static void checkCondition(
            String table, EntityField field, @Nullable FieldCondition condition, Map<String, EntityField> byKey) {
        if (condition == null) return;
        for (String key : condition.fields()) {
            EntityField tested = byKey.get(key);
            if (tested == null || tested.form() == null || !FieldCondition.TESTED_TYPES.contains(tested.type())) {
                throw new IllegalArgumentException("Table " + table + ": the condition of " + field.key() + " looks at "
                        + key + ", which is no select, enumeration, yes/no or reference of the form");
            }
        }
    }
}
