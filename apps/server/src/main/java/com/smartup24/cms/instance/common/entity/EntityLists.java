package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The lists of the entities, derived from their fields (ADR-0032, 3.4; plan 10/10, item 5.1). The list registry takes
 * them as a {@link QueryListSource}, next to the lists declared as beans; a bean with the code of an entity's list
 * fails the start. Built from the declarations alone, not from {@link EntityRegistry}, whose records beans need the
 * modules' services, and those the registry of lists.
 *
 * <p>The list of an entity: its code is the entity's list code, its right {@code <form>.view}; it reads
 * {@code <table> <alias>}; it selects the system columns, every field as {@code <sql> as "<key>"} and the record's
 * {@code attributes} as text — columns every entity table has (ADR-0032, 14.1) — so a row is read by the record's
 * keys; its fields are the list parts of the entity's fields in declaration order. Only an entity that takes custom
 * fields offers them in the list ({@code customEntity}, {@code attributesSql}).
 */
@Component
public class EntityLists implements QueryListSource {

    /** The property of the custom field values in a row. */
    public static final String ATTRIBUTES = EntityModel.ATTRIBUTES;

    private final List<QueryList> lists;

    public EntityLists(List<EntityDefinition> entities) {
        this.lists = entities.stream()
                .filter(entity -> entity.model() != null)
                .map(EntityLists::queryList)
                .toList();
    }

    @Override
    public List<QueryList> lists() {
        return lists;
    }

    /** The list of an entity with a model. */
    public static QueryList queryList(EntityDefinition entity) {
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        String alias = model.alias();
        @Nullable String attributes = entity.customEntity() == null ? null : alias + "." + ATTRIBUTES;
        return new QueryList(
                Objects.requireNonNull(entity.listCode(), entity.code()),
                entity.form(),
                "view",
                select(model),
                model.table() + " " + alias,
                alias + ".id",
                model.listFields(),
                model.defaultSort(),
                model.defaultDescending(),
                QueryList.DEFAULT_LIMIT,
                QueryList.MAX_LIMIT,
                entity.customEntity(),
                attributes,
                false);
    }

    private static String select(EntityModel model) {
        String alias = model.alias();
        List<String> columns = new ArrayList<>();
        for (SystemColumn column : SystemColumn.values()) {
            columns.add(alias + "." + column.column() + " as \"" + column.key() + "\"");
        }
        for (EntityField field : model.fields()) {
            if (field.source() instanceof FieldSource.SystemValue) continue;
            columns.add(field.source().sql(alias) + " as \"" + field.key() + "\"");
        }
        columns.add(alias + "." + ATTRIBUTES + "::text as \"" + ATTRIBUTES + "\"");
        return String.join(", ", columns);
    }
}
