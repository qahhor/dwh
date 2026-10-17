package com.smartup24.cms.instance.md.pref;

import com.smartup24.cms.instance.md.api.MdPref;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dictionary keys naming forms and actions (FR-PERM-1, ADR-0031).
 *
 * The split of responsibility is deliberate: the **existence** of a "form x action" pair
 * is defined by {@code @RequiresPermission} annotations in controllers; they are the
 * single source of truth because they are what actually guards the endpoints.
 * Only the names for the permission matrix screen live here.
 *
 * Forms of entities declared through {@code EntityDefinition} name themselves
 * ({@code EntityRights}) and are not repeated here. The owning module of a form is not
 * written here either: it follows from the code by the rule of ADR-0028 ({@link PermissionAreas}).
 *
 * A pair declared by an annotation but forgotten here gets its own code as its name
 * and does not break anything; the build is then failed by the
 * {@code everyDeclaredPermissionHasHumanName} test. The reverse case, a name without
 * an annotation, means a dead catalog entry and is marked obsolete.
 */
public final class MdFormCatalog {

    private MdFormCatalog() {}

    /** A form: the dictionary keys of its name and action names. */
    public record FormMeta(String nameKey, Map<String, String> actionKeys) {}

    private static final Map<String, FormMeta> FORMS = buildForms();

    public static Optional<FormMeta> find(String formCode) {
        return Optional.ofNullable(FORMS.get(formCode));
    }

    /**
     * The form's owning module by the rule of ADR-0028; for a code outside the rule, its first segment, so grouping in
     * the UI does not fall apart.
     */
    public static String moduleOf(String formCode) {
        return PermissionAreas.ownerOf(formCode).orElseGet(() -> PermissionAreas.areaOf(formCode));
    }

    public static String formNameKeyOf(String formCode) {
        var meta = FORMS.get(formCode);
        return meta != null ? meta.nameKey() : formCode;
    }

    public static String actionNameKeyOf(String formCode, String action) {
        var meta = FORMS.get(formCode);
        if (meta == null) {
            return action;
        }
        return meta.actionKeys().getOrDefault(action, action);
    }

    /** Whether the pair has a name; used by the catalog completeness test. */
    public static boolean hasHumanName(String formCode, String action) {
        var meta = FORMS.get(formCode);
        return meta != null && meta.actionKeys().containsKey(action);
    }

    private static Map<String, FormMeta> buildForms() {
        Map<String, FormMeta> forms = new LinkedHashMap<>();
        putIdentityForms(forms);
        putAdministrationForms(forms);
        putUploadAndTaskForms(forms);
        putCommunicationForms(forms);
        putPlatformForms(forms);
        return Map.copyOf(forms);
    }

    /** Profile, users, roles and assignments: who the user is and what they may do. */
    private static void putIdentityForms(Map<String, FormMeta> forms) {
        forms.put(
                MdPref.FORM_PROFILE,
                new FormMeta(
                        "md.profile.rights.form",
                        ordered(
                                "view", "md.profile.rights.view",
                                "update", "md.profile.rights.update",
                                "manage_channels", "md.profile.rights.manage_channels",
                                "manage_tokens", "md.profile.rights.manage_tokens")));

        forms.put(
                MdPref.FORM_ROLES,
                new FormMeta(
                        "md.roles.rights.form",
                        ordered(
                                "view", "md.roles.rights.view",
                                "create", "md.roles.rights.create",
                                "update", "md.roles.rights.update",
                                "delete", "md.roles.rights.delete",
                                "grant", "md.roles.rights.grant")));

        forms.put(
                MdPref.FORM_ASSIGNMENTS,
                new FormMeta(
                        "md.assignments.rights.form",
                        ordered(
                                "view", "md.assignments.rights.view",
                                "assign", "md.assignments.rights.assign")));
    }

    private static void putAdministrationForms(Map<String, FormMeta> forms) {
        forms.put(
                MdPref.FORM_CUSTOM_FIELDS,
                new FormMeta(
                        "md.custom_fields.rights.form",
                        ordered(
                                "view", "md.custom_fields.rights.view",
                                "create", "md.custom_fields.rights.create",
                                "update", "md.custom_fields.rights.update",
                                "delete", "md.custom_fields.rights.delete")));

        forms.put(
                MdPref.FORM_ORG_UNITS,
                new FormMeta(
                        "md.org_units.rights.form",
                        ordered(
                                "view", "md.org_units.rights.view",
                                "create", "md.org_units.rights.create",
                                "update", "md.org_units.rights.update",
                                "delete", "md.org_units.rights.delete",
                                "assign", "md.org_units.rights.assign")));

        forms.put(
                MdPref.FORM_SETTINGS,
                new FormMeta(
                        "md.settings.rights.form",
                        ordered(
                                "view", "md.settings.rights.view",
                                "update", "md.settings.rights.update")));

        forms.put("audit.log", new FormMeta("audit.log.rights.form", ordered("view", "audit.log.rights.view")));
    }

    private static void putUploadAndTaskForms(Map<String, FormMeta> forms) {
        forms.put(
                "upl.sources",
                new FormMeta(
                        "upl.sources.rights.form",
                        ordered(
                                "view", "upl.sources.rights.view",
                                "create", "upl.sources.rights.create",
                                "edit", "upl.sources.rights.edit",
                                "publish", "upl.sources.rights.publish")));

        forms.put(
                "upl.packages",
                new FormMeta(
                        "upl.packages.rights.form",
                        ordered(
                                "view", "upl.packages.rights.view",
                                "upload", "upl.packages.rights.upload",
                                "apply", "upl.packages.rights.apply")));

        forms.put(
                "tasks.projects",
                new FormMeta(
                        "tasks.projects.rights.form",
                        ordered(
                                "view", "tasks.projects.rights.view",
                                "create", "tasks.projects.rights.create",
                                "update", "tasks.projects.rights.update")));

        forms.put(
                "tasks.items",
                new FormMeta(
                        "tasks.items.rights.form",
                        ordered(
                                "view", "tasks.items.rights.view",
                                "create", "tasks.items.rights.create",
                                "update", "tasks.items.rights.update")));

        forms.put(
                "tasks.comments",
                new FormMeta(
                        "tasks.comments.rights.form",
                        ordered(
                                "view", "tasks.comments.rights.view",
                                "create", "tasks.comments.rights.create")));
    }

    /** Notifications, announcements, files and search. */
    private static void putCommunicationForms(Map<String, FormMeta> forms) {
        forms.put(
                "notify.inbox", new FormMeta("notify.inbox.rights.form", ordered("view", "notify.inbox.rights.view")));

        forms.put(
                "notify.announcements",
                new FormMeta(
                        "notify.announcements.rights.form",
                        ordered(
                                "view", "notify.announcements.rights.view",
                                "create", "notify.announcements.rights.create",
                                "update", "notify.announcements.rights.update",
                                "publish", "notify.announcements.rights.publish",
                                "archive", "notify.announcements.rights.archive")));

        forms.put(
                "mf.files",
                new FormMeta(
                        "mf.files.rights.form",
                        ordered(
                                "view", "mf.files.rights.view",
                                "upload", "mf.files.rights.upload",
                                "delete", "mf.files.rights.delete")));

        forms.put("search", new FormMeta("search.rights.form", ordered("view", "search.rights.view")));
    }

    /** Webhooks, analytics, modules and navigation. */
    private static void putPlatformForms(Map<String, FormMeta> forms) {
        forms.put(
                "studio.forms",
                new FormMeta(
                        "studio.forms.rights.form",
                        ordered("publish", "studio.forms.rights.publish", "assist", "studio.forms.rights.assist")));
        forms.put(
                "studio.lists",
                new FormMeta(
                        "studio.lists.rights.form",
                        ordered("view", "studio.lists.rights.view", "manage", "studio.lists.rights.manage")));
        forms.put(
                "webhook.subscriptions",
                new FormMeta(
                        "webhook.subscriptions.rights.form",
                        ordered(
                                "view", "webhook.subscriptions.rights.view",
                                "manage", "webhook.subscriptions.rights.manage")));

        forms.put(
                "analytics.dashboard",
                new FormMeta(
                        "analytics.dashboard.rights.form",
                        ordered(
                                "view", "analytics.dashboard.rights.view",
                                "manage", "analytics.dashboard.rights.manage")));

        forms.put(
                MdPref.FORM_MODULES,
                new FormMeta(
                        "md.modules.rights.form",
                        ordered(
                                "view", "md.modules.rights.view",
                                "manage", "md.modules.rights.manage")));

        forms.put(
                MdPref.FORM_NAVIGATION,
                new FormMeta(
                        "md.navigation.rights.form",
                        ordered(
                                "view", "md.navigation.rights.view",
                                "manage", "md.navigation.rights.manage")));
    }

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return Map.copyOf(map);
    }
}
