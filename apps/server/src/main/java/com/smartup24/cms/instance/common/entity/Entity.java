package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityRights;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.EntityFields;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The way a module declares an entity (ADR-0032, 3.2): its table, its fields — each once, as an {@link EntityField} —
 * its form layout, rights, menu item, actions and capabilities. {@link #build()} derives the form fields and the list
 * code from the fields; the list itself is derived by {@link EntityLists}.
 *
 * <pre>{@code
 * Entity.define("ms.notes", "notes")
 *         .table("ms_notes", "n")
 *         .field(text("title", "notes.col.title").column("title").required().length(1, 255)
 *                 .list(sortable().searchable()))
 *         .section("main", "entity.section.main", "title")
 *         .actions("create", "update", "delete")
 *         .defaultSort("title", Entity.Sort.ASC)
 *         .build();
 * }</pre>
 */
public final class Entity {

    /** The direction of the list's default order. */
    public enum Sort {
        ASC,
        DESC
    }

    private final String code;
    private final String form;
    private @Nullable String table;
    private @Nullable String alias;
    private @Nullable EntityRights rights;
    private @Nullable EntityMenu menu;
    private @Nullable String customEntity;
    private @Nullable String auditTable;
    private @Nullable String defaultSort;
    private boolean defaultDescending;
    private final List<EntityField> fields = new ArrayList<>();
    private final List<FormSection> layout = new ArrayList<>();
    private final List<EntityAction> actions = new ArrayList<>();
    private final Set<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);

    private Entity(String code, String form) {
        this.code = Objects.requireNonNull(code, "code");
        this.form = Objects.requireNonNull(form, "form");
    }

    /** An entity with its code ({@code ms.notes}) and the form of its right ({@code notes}). */
    public static Entity define(String code, String form) {
        return new Entity(code, form);
    }

    /** The table of its records and its alias in the list's SQL; the entity then has a list under its code. */
    public Entity table(String name, String tableAlias) {
        this.table = name;
        this.alias = tableAlias;
        return this;
    }

    /** The owning module and the dictionary keys of the right's and its actions' names (ADR-0031). */
    public Entity rights(String module, String nameKey, Map<String, String> actionKeys) {
        this.rights = new EntityRights(module, nameKey, actionKeys);
        return this;
    }

    public Entity menu(EntityMenu item) {
        this.menu = item;
        return this;
    }

    public Entity field(EntityFields.Builder field) {
        return field(field.build());
    }

    public Entity field(EntityField field) {
        fields.add(field);
        return this;
    }

    /** A titled group of form fields, by their keys. */
    public Entity section(String key, String labelKey, String... fieldKeys) {
        layout.add(new FormSection(key, labelKey, List.of(fieldKeys)));
        return this;
    }

    /** Actions that each need the action of the right with the same name. */
    public Entity actions(String... codes) {
        for (String action : codes) {
            action(action, action);
        }
        return this;
    }

    /** An action that needs {@code permission} of the right ({@code pin} needs {@code update}). */
    public Entity action(String action, String permission) {
        actions.add(new EntityAction(action, permission));
        return this;
    }

    /** The list's default order: a sortable list field. */
    public Entity defaultSort(String key, Sort direction) {
        this.defaultSort = key;
        this.defaultDescending = direction == Sort.DESC;
        return this;
    }

    /** Administrator-defined fields of {@code entityType} ({@code NOTE}) in the record's attributes (ADR-0019, 2.3). */
    public Entity customFields(String entityType) {
        this.customEntity = entityType;
        capabilities.add(EntityCapability.CUSTOM_FIELDS);
        return this;
    }

    /** The {@code audit_log.table_name} of its history when it is not its table. */
    public Entity auditTable(String name) {
        this.auditTable = name;
        return this;
    }

    public Entity capabilities(EntityCapability... provided) {
        capabilities.addAll(List.of(provided));
        return this;
    }

    public EntityDefinition build() {
        @Nullable EntityModel model = null;
        if (table != null) {
            model = new EntityModel(
                    table,
                    Objects.requireNonNull(alias, "alias"),
                    fields,
                    Objects.requireNonNull(defaultSort, "Entity " + code + " names its list's default sort"),
                    defaultDescending);
        } else if (fields.stream().anyMatch(field -> field.list() != null)) {
            throw new IllegalArgumentException("Entity " + code + ": a list field needs the entity's table");
        }
        List<FormField> formFields = fields.stream()
                .flatMap(field -> Stream.ofNullable(field.formField()))
                .toList();
        @Nullable
        String history = auditTable != null || !capabilities.contains(EntityCapability.HISTORY) ? auditTable : table;
        return new EntityDefinition(
                code,
                form,
                model == null ? null : code,
                customEntity,
                history,
                rights,
                menu,
                formFields,
                layout,
                actions,
                capabilities,
                model);
    }
}
