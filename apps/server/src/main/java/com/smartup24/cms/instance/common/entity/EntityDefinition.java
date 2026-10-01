package com.smartup24.cms.instance.common.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An entity declared once (ADR-0019, 2.1): its right, the actions on it and the right each needs, its list in
 * the field registry, the fields and layout of its form, and what the platform gives it. A module declares it with
 * {@link Entity#define} as a bean; {@link EntityRegistry} collects them and {@code GET /api/v1/form-meta/{code}}
 * serves them.
 *
 * <p>An entity with a table has an {@link EntityModel}: every field is declared there once (ADR-0032, 3; plan 10/10,
 * item 5.1), the form fields are derived from it and so is the entity's list ({@link EntityLists}). An entity has a
 * list only through its model, so no module declares the same field for the form and again for the list.
 *
 * @param code         the entity's code ({@code ms.notes}); usually its list's code
 * @param form         the right's form ({@code notes}); viewing needs {@code form.view}
 * @param listCode     its list in the field registry, derived from {@code model}; null without a model
 * @param customEntity the entity type of its custom fields ({@code NOTE}), or null
 * @param auditTable   the {@code audit_log.table_name} its changes are written under ({@code ms_notes}), or null
 * @param rights       the names of its right and the right's actions in the permission matrix, or null
 * @param menu         its item in the side menu, or null
 * @param fields       the form's fields, in order: the model's form fields first, then the custom fields
 * @param layout       sections of the form; every field appears in exactly one
 * @param actions      what can be done, each with the action of the right it needs
 * @param capabilities what the platform provides for it
 * @param model        its table and its fields, each declared once, or null for a form without a table
 */
public record EntityDefinition(
        String code,
        String form,
        @Nullable String listCode,
        @Nullable String customEntity,
        @Nullable String auditTable,
        @Nullable EntityRights rights,
        @Nullable EntityMenu menu,
        List<FormField> fields,
        List<FormSection> layout,
        List<EntityAction> actions,
        Set<EntityCapability> capabilities,
        @Nullable EntityModel model) {

    /**
     * How the permission matrix names the entity's right (roadmap item 57): the owning module, the dictionary key
     * of the form's name and the key of each action's name (ADR-0031, plan 10/10, item 5.0), so the matrix reads
     * in the viewer's language. Every action the entity declares and {@code view} are named.
     */
    public record EntityRights(String module, String nameKey, Map<String, String> actionKeys) {
        public EntityRights {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(nameKey, "nameKey");
            actionKeys = Map.copyOf(actionKeys);
        }

        /** The keys of the form's and its actions' names, the form's first. */
        public List<String> keys() {
            List<String> keys = new ArrayList<>();
            keys.add(nameKey);
            actionKeys.values().stream().sorted().forEach(keys::add);
            return keys;
        }
    }

    /**
     * The entity's item in the side menu (roadmap item 57): where it leads, its label and icon, the menu section
     * ({@code workspace}, {@code iam}, {@code administration}) and its place there, and the installed module
     * whose switch hides it, or null when it is always on.
     */
    public record EntityMenu(
            String route,
            String labelKey,
            String icon,
            String section,
            int order,
            @Nullable String module) {
        public EntityMenu {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(labelKey, "labelKey");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(section, "section");
        }
    }

    /** An action on the entity and the action of the entity's right it needs ({@code create} → {@code notes.create}). */
    public record EntityAction(String code, String permission) {
        public EntityAction {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(permission, "permission");
        }
    }

    /** A titled group of fields on the form. */
    public record FormSection(String key, String labelKey, List<String> fields) {
        public FormSection {
            fields = List.copyOf(fields);
        }
    }

    /** The action a bulk delete runs as, one record at a time. */
    public static final String DELETE = "delete";

    /** The action that archives and restores a record (ADR-0032, 5.4). */
    public static final String ARCHIVE = "archive";

    public EntityDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(form, "form");
        fields = List.copyOf(fields);
        layout = List.copyOf(layout);
        actions = List.copyOf(actions);
        capabilities = Set.copyOf(capabilities);
        Set<String> keys = new HashSet<>();
        for (FormField field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("Entity " + code + ": duplicate field " + field.key());
            }
        }
        Set<String> placed = new HashSet<>();
        for (FormSection section : layout) {
            for (String key : section.fields()) {
                if (!keys.contains(key) || !placed.add(key)) {
                    throw new IllegalArgumentException("Entity " + code + ": section " + section.key()
                            + " places an unknown or repeated field " + key);
                }
            }
        }
        if (!placed.equals(keys)) {
            throw new IllegalArgumentException("Entity " + code + ": every field belongs to one section");
        }
        if ((listCode == null) != (model == null)) {
            throw new IllegalArgumentException("Entity " + code
                    + ": its list is derived from its fields, declared once as EntityField (plan 10/10, item 5.1)");
        }
        if (model != null) {
            List<FormField> declared = model.formFields();
            if (fields.size() < declared.size()
                    || !fields.subList(0, declared.size()).equals(declared)) {
                throw new IllegalArgumentException("Entity " + code + ": its form fields come from its model");
            }
        }
        if (capabilities.contains(EntityCapability.CUSTOM_FIELDS) != (customEntity != null)) {
            throw new IllegalArgumentException(
                    "Entity " + code + ": custom fields need their entity type, and only then");
        }
        if (capabilities.contains(EntityCapability.HISTORY) != (auditTable != null)) {
            throw new IllegalArgumentException("Entity " + code + ": history needs its audit table, and only then");
        }
        if ((capabilities.contains(EntityCapability.EXPORT) || capabilities.contains(EntityCapability.SAVED_VIEWS))
                && listCode == null) {
            throw new IllegalArgumentException("Entity " + code + ": export and saved views need its list");
        }
        if (capabilities.contains(EntityCapability.BULK) && actions.stream().noneMatch(a -> DELETE.equals(a.code()))) {
            throw new IllegalArgumentException("Entity " + code + ": bulk delete needs the delete action");
        }
        requireArchive(code, capabilities, actions, model);
        if (rights != null) {
            Set<String> named = rights.actionKeys().keySet();
            if (!named.contains("view") || actions.stream().anyMatch(action -> !named.contains(action.permission()))) {
                throw new IllegalArgumentException("Entity " + code + ": name view and every action's right");
            }
        }
    }

    /** The ARCHIVE capability goes with the {@code archive} action and a table that keeps the archived rows. */
    private static void requireArchive(
            String code, Set<EntityCapability> capabilities, List<EntityAction> actions, @Nullable EntityModel model) {
        boolean archivable = capabilities.contains(EntityCapability.ARCHIVE);
        if (archivable != actions.stream().anyMatch(a -> ARCHIVE.equals(a.code()))) {
            throw new IllegalArgumentException(
                    "Entity " + code + ": the archive capability goes with the archive action, and only then");
        }
        if (archivable && model == null) {
            throw new IllegalArgumentException("Entity " + code + ": archived records live in the entity's table");
        }
    }

    /** An entity without a table: its form only, no list (ADR-0019, 2.1). */
    public EntityDefinition(
            String code,
            String form,
            @Nullable String customEntity,
            @Nullable String auditTable,
            @Nullable EntityRights rights,
            @Nullable EntityMenu menu,
            List<FormField> fields,
            List<FormSection> layout,
            List<EntityAction> actions,
            Set<EntityCapability> capabilities) {
        this(code, form, null, customEntity, auditTable, rights, menu, fields, layout, actions, capabilities, null);
    }

    /** The same entity with these form fields and layout: its custom fields added ({@link EntityRegistry}). */
    public EntityDefinition withForm(List<FormField> formFields, List<FormSection> formLayout) {
        return new EntityDefinition(
                code,
                form,
                listCode,
                customEntity,
                auditTable,
                rights,
                menu,
                formFields,
                formLayout,
                actions,
                capabilities,
                model);
    }

    public Optional<EntityAction> action(String code) {
        return actions.stream().filter(action -> action.code().equals(code)).findFirst();
    }

    public Map<String, FormField> fieldsByKey() {
        Map<String, FormField> byKey = new LinkedHashMap<>();
        fields.forEach(field -> byKey.put(field.key(), field));
        return byKey;
    }
}
