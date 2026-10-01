package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.query.QueryField;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where an entity's records live and the fields they have (ADR-0032, 3.2): the table and its alias, every field
 * declared once as an {@link EntityField}, and the list's default order. The entity's form fields
 * ({@link #formFields()}) and its list ({@link EntityLists#queryList}) are derived from it. The scope, collections,
 * process, rules, search and import of ADR-0032 join it with the later items of plan 10/10, phase 5.
 *
 * @param table             the entity's table ({@code ms_notes})
 * @param alias             its alias in the list's SQL ({@code n})
 * @param fields            every field, in the order of the form and the list
 * @param defaultSort       the key of the list's default sort field, a sortable list field
 * @param defaultDescending the default sort runs from the largest value
 */
public record EntityModel(
        String table, String alias, List<EntityField> fields, String defaultSort, boolean defaultDescending) {

    public EntityModel {
        requireIdentifier(table);
        requireIdentifier(alias);
        fields = List.copyOf(fields);
        Objects.requireNonNull(defaultSort, "defaultSort");
        Set<String> keys = new HashSet<>();
        for (EntityField field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("Table " + table + ": duplicate field " + field.key());
            }
        }
    }

    /** The fields of the form, in declaration order. */
    public List<FormField> formFields() {
        return fields.stream()
                .flatMap(field -> Stream.ofNullable(field.formField()))
                .toList();
    }

    /** The fields of the list, in declaration order, read over the alias. */
    public List<QueryField> listFields() {
        return fields.stream()
                .flatMap(field -> Stream.ofNullable(field.queryField(alias)))
                .toList();
    }

    private static void requireIdentifier(String name) {
        if (name == null || !FieldSource.IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Bad identifier: " + name);
        }
    }
}
