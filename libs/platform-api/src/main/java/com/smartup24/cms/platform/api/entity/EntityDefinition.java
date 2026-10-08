package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An entity declared once (ADR-0019, 2.1): its right, the actions on it and the right each needs, its list in
 * the field registry, the fields and layout of its form, and what the platform gives it. A module declares it with
 * {@link Entity#define} as a bean; the platform's registry collects them and
 * {@code GET /api/v1/form-meta/{code}} serves them.
 *
 * <p>An entity with a table has an {@link EntityModel}: every field is declared there once (ADR-0032, 3; plan 10/10,
 * item 5.1), the form fields are derived from it and so is the entity's list. An entity has a
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
@PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
     * whose switch hides it, or null when it is always on. Without a route of its own the item leads to the general
     * screen of the entity, {@code /e/<code>} (ADR-0032, 7.1): only a screen with another way of working (a board, a
     * calendar, a wizard; ADR-0032, 7.2) names its route.
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record EntityMenu(
            @Nullable String route,
            String labelKey,
            String icon,
            String section,
            int order,
            @Nullable String module) {
        public EntityMenu {
            Objects.requireNonNull(labelKey, "labelKey");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(section, "section");
        }

        /** An item that leads to the entity's general screen. */
        public EntityMenu(String labelKey, String icon, String section, int order, @Nullable String module) {
            this(null, labelKey, icon, section, order, module);
        }

        /** Where the item of entity {@code code} leads: its own route, or the general screen {@code /e/<code>}. */
        public String routeFor(String code) {
            return route != null ? route : GENERAL_SCREEN + code;
        }
    }

    /** The route prefix of the general entity screen in the web application (ADR-0032, 7.1). */
    public static final String GENERAL_SCREEN = "/e/";

    /**
     * An action on the entity and the action of the entity's right it needs ({@code create} → {@code notes.create}): a
     * record action — run by the runtime itself or by its handler — or a transition of the entity's process (ADR-0032,
     * 6.7 and 9.2), with the dictionary key of the question the screen asks first, or null.
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record EntityAction(
            String code,
            String permission,
            Kind kind,
            @Nullable String confirmKey) {
        public EntityAction {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(kind, "kind");
        }

        /** A record action without a question. */
        public EntityAction(String code, String permission) {
            this(code, permission, Kind.RECORD, null);
        }

        /** What an action is. */
        @PlatformApi(since = "1.0", stability = Stability.STABLE)
        public enum Kind {
            /** Done by the runtime itself (create, update, archive, delete) or by the action's handler. */
            RECORD,
            /** A transition of the entity's process (ADR-0032, 9.2). */
            TRANSITION
        }
    }

    /** A titled group of fields on the form. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record FormSection(String key, String labelKey, List<String> fields) {
        public FormSection {
            fields = List.copyOf(fields);
        }
    }

    /** The form section the platform adds for the administrator's custom fields (ADR-0019, 2.3). */
    public static final String CUSTOM_SECTION = "custom";

    /** The action a bulk delete runs as, one record at a time. */
    public static final String DELETE = "delete";

    /** The action that archives and restores a record (ADR-0032, 5.4). */
    public static final String ARCHIVE = "archive";

    /**
     * The right's action an import needs (ADR-0032, 10.1 and 6.10): not an action on a record, so it is in no record's
     * {@code actions}; {@code form-meta} names it when the entity declares IMPORT and the viewer holds it.
     */
    public static final String IMPORT = "import";

    public EntityDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(form, "form");
        fields = fields instanceof KeyedFields keyed ? keyed : new KeyedFields(fields);
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
        // A bulk action is a change of each record: delete, archive, update or a record action (ADR-0032, 6.1).
        if (capabilities.contains(EntityCapability.BULK) && actions.stream().allMatch(a -> "create".equals(a.code()))) {
            throw new IllegalArgumentException("Entity " + code + ": bulk actions need an action on a record");
        }
        requireArchive(code, capabilities, actions, model);
        requireSearch(code, capabilities, model);
        requireTabs(code, capabilities, layout, model);
        if (rights != null) {
            Set<String> named = rights.actionKeys().keySet();
            if (!named.contains("view") || actions.stream().anyMatch(action -> !named.contains(action.permission()))) {
                throw new IllegalArgumentException("Entity " + code + ": name view and every action's right");
            }
        }
    }

    /** The SEARCH capability goes with the search spec of the model (ADR-0032, 10.3). */
    private static void requireSearch(String code, Set<EntityCapability> capabilities, @Nullable EntityModel model) {
        if (capabilities.contains(EntityCapability.SEARCH) != (model != null && model.search() != null)) {
            throw new IllegalArgumentException("Entity " + code + ": the search capability goes with its search spec");
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

    /**
     * The tabs of the card fit the form (ADR-0032, 9.3): once a tab shows sections, every declared section is on exactly
     * one tab — the section of the administrator's custom fields goes with the first such tab — and a history tab needs
     * the history.
     */
    private static void requireTabs(
            String code, Set<EntityCapability> capabilities, List<FormSection> layout, @Nullable EntityModel model) {
        if (model == null || model.tabs().isEmpty()) return;
        Set<String> sections = new HashSet<>();
        layout.forEach(section -> sections.add(section.key()));
        sections.remove(CUSTOM_SECTION);
        Set<String> placed = new HashSet<>();
        boolean sectionTabs = false;
        for (EntityTab tab : model.tabs()) {
            if (tab.kind() == EntityTab.Kind.HISTORY && !capabilities.contains(EntityCapability.HISTORY)) {
                throw new IllegalArgumentException("Entity " + code + ": a history tab needs the history");
            }
            for (String section : tab.sections()) {
                sectionTabs = true;
                if (!sections.contains(section) || !placed.add(section)) {
                    throw new IllegalArgumentException(
                            "Entity " + code + ": the tab " + tab.key() + " shows an unknown or repeated section");
                }
            }
        }
        if (sectionTabs && !placed.equals(sections)) {
            throw new IllegalArgumentException("Entity " + code + ": every section of the form is on one tab");
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

    /** The same entity with these form fields and layout: its custom fields added by the platform's registry. */
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

    /** The form fields by key, in order: built once with the entity, read-only. */
    public Map<String, FormField> fieldsByKey() {
        return ((KeyedFields) fields).byKey;
    }

    /**
     * The form fields, immutable, with their index by key built once: the runtime looks a field up by key for every
     * value of a save (ADR-0032, 6.5). Equal to any list of the same fields, so the record's equality is unchanged.
     */
    private static final class KeyedFields extends AbstractList<FormField> implements RandomAccess {

        private final List<FormField> fields;
        private final Map<String, FormField> byKey;

        KeyedFields(List<FormField> fields) {
            this.fields = List.copyOf(fields);
            Map<String, FormField> index = new LinkedHashMap<>();
            this.fields.forEach(field -> index.put(field.key(), field));
            this.byKey = Collections.unmodifiableMap(index);
        }

        @Override
        public FormField get(int index) {
            return fields.get(index);
        }

        @Override
        public int size() {
            return fields.size();
        }
    }
}
