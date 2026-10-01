package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityRights;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.EntityFields;
import com.smartup24.cms.instance.common.entity.hook.EntityRule;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The way a module declares an entity (ADR-0032, 3.2): its table and its scope, its fields — each once, as an
 * {@link EntityField} — its form layout, rights, menu item, actions and capabilities. {@link #build()} derives the form
 * fields and the list code from the fields; the list itself is derived by {@link EntityLists}.
 *
 * <pre>{@code
 * Entity.define("ms.notes", "notes")
 *         .table("ms_notes", "n")
 *         .scope(EntityScope.owner("created_by"))
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
    private @Nullable EntityScope scope;
    private @Nullable EntityRights rights;
    private @Nullable EntityMenu menu;
    private @Nullable String customEntity;
    private @Nullable String auditTable;
    private @Nullable String defaultSort;
    private boolean defaultDescending;
    private @Nullable EntityReference reference;
    private final List<EntityField> fields = new ArrayList<>();
    private final List<FormSection> layout = new ArrayList<>();
    private final List<EntityAction> actions = new ArrayList<>();
    private final Set<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
    private final Map<String, EntityRule> rules = new LinkedHashMap<>();

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

    /** Which rows of its table a viewer sees (ADR-0032, 5.1): an entity with a table cannot be built without it. */
    public Entity scope(EntityScope rows) {
        this.scope = Objects.requireNonNull(rows, "scope");
        return this;
    }

    /**
     * The ARCHIVE capability (ADR-0032, 5.4): records are archived and restored instead of only deleted, with the
     * action {@code archive} that needs the right's {@code delete} — the default of ADR-0032, 19 (question 11),
     * taken as an assumption until the product owner answers it.
     */
    public Entity archivable() {
        return archivable(EntityDefinition.DELETE);
    }

    /** The ARCHIVE capability whose action needs {@code permission} of the right. */
    public Entity archivable(String permission) {
        capabilities.add(EntityCapability.ARCHIVE);
        return action(EntityDefinition.ARCHIVE, permission);
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

    /**
     * A cross-field rule (ADR-0032, 6.6), named for the review: {@code .rule("period", Rules.notBefore("endDate",
     * "startDate"))}. Its problems join the problems of the fields in one 422 of every save.
     */
    public Entity rule(String name, EntityRule rule) {
        if (rules.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(rule, "rule")) != null) {
            throw new IllegalArgumentException("Entity " + code + ": duplicate rule " + name);
        }
        return this;
    }

    /** The list's default order: a sortable list field. */
    public Entity defaultSort(String key, Sort direction) {
        this.defaultSort = key;
        this.defaultDescending = direction == Sort.DESC;
        return this;
    }

    /**
     * Its rows are the items of enumerations (ADR-0032, 4.5): the code column, the name column, in the order of
     * {@code sort_order}.
     */
    public Entity reference(String codeColumn, String nameColumn) {
        return reference(codeColumn, nameColumn, "sort_order");
    }

    /** Its rows are the items of enumerations, in the order of {@code orderColumn}. */
    public Entity reference(String codeColumn, String nameColumn, String orderColumn) {
        this.reference = new EntityReference(codeColumn, nameColumn, orderColumn);
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
            if (scope == null) {
                throw new IllegalArgumentException(
                        "Entity " + code + " declares which rows a viewer sees: .scope(...) (ADR-0032, 5.1)");
            }
            model = new EntityModel(
                    table,
                    Objects.requireNonNull(alias, "alias"),
                    fields,
                    Objects.requireNonNull(defaultSort, "Entity " + code + " names its list's default sort"),
                    defaultDescending,
                    scope,
                    reference,
                    rules);
        } else if (!rules.isEmpty()) {
            throw new IllegalArgumentException("Entity " + code + ": a rule checks the records of its table");
        } else if (reference != null || fields.stream().anyMatch(field -> field.list() != null)) {
            throw new IllegalArgumentException("Entity " + code + ": a list field needs the entity's table");
        } else if (scope != null) {
            throw new IllegalArgumentException("Entity " + code + ": a scope restricts the rows of its table");
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
