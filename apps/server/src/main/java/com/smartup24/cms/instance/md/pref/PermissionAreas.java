package com.smartup24.cms.instance.md.pref;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one rule for permission codes (ADR-0028, plan 10/10, item 4.4): a form code is {@code <area>} or
 * {@code <area>.<entity-or-screen>}, and its first segment, the area, names exactly one owning module. A permission
 * key is {@code <form>.<action>}.
 *
 * <p>An area is a business module code ({@code md}, {@code mf}, {@code upl}, ...) or one of the named areas below for
 * a module whose code does not fit one segment ({@code ms.task}) or is about to change ({@code kwh}, plan 10/10, item
 * 4.3). A controller guards its endpoints with forms of its own module; another module's form only when the owner
 * publishes it to that module ({@link #PUBLISHED}), the way ADR-0026 publishes read views. {@code PermissionCodesTest}
 * checks every {@code @RequiresPermission} against both tables.
 */
public final class PermissionAreas {

    private PermissionAreas() {}

    /** One lower-case segment: letters, digits and underscores, starting with a letter. */
    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9_]*");

    /** Areas that are business module codes themselves. */
    private static final Set<String> MODULE_AREAS =
            Set.of("analytics", "audit", "jobs", "kauth", "md", "mf", "report", "search", "units", "upl", "warehouse");

    /** Named areas and their owning modules. */
    private static final Map<String, String> NAMED_AREAS = Map.of(
            "tasks", "ms.task",
            "notes", "ms.note",
            "notify", "ms.notify",
            // Plan 10/10, item 4.3 renames the module kwh to webhook; the area already carries the new name.
            "webhook", "kwh");

    /**
     * Forms a module publishes to other modules, with the modules that may guard endpoints with them. Each entry is a
     * reason recorded in ADR-0028: the endpoint serves the owner's resource, so splitting the right would only make an
     * administrator grant the same thing twice.
     */
    public static final Map<String, Set<String>> PUBLISHED = Map.of(
            // The signed-in user's own account: own sessions, tokens and channels (kauth), own exports (report),
            // the history of records the user may open (audit, which checks the record's own right as well).
            MdPref.FORM_PROFILE,
            Set.of("audit", "kauth", "report"),
            // The sessions and security block of the user card belong to user administration.
            MdPref.FORM_USERS,
            Set.of("kauth"),
            // Platform settings: sign-in providers (kauth) and search settings and index jobs (search).
            MdPref.FORM_SETTINGS,
            Set.of("kauth", "search"),
            // The task report exports the task list under the right that shows it.
            "tasks.items",
            Set.of("report"));

    /**
     * Form codes of earlier releases and their successors (V147). Requests that grant rights or bind a menu item to a
     * right still accept them until the sunset of the deprecated API forms (2026-12-31, ADR-0023).
     */
    public static final Map<String, String> LEGACY_FORMS = Map.ofEntries(
            Map.entry("iam.users", MdPref.FORM_USERS),
            Map.entry("iam.profile", MdPref.FORM_PROFILE),
            Map.entry("iam.org_units", MdPref.FORM_ORG_UNITS),
            Map.entry("rbac.roles", MdPref.FORM_ROLES),
            Map.entry("rbac.assignments", MdPref.FORM_ASSIGNMENTS),
            Map.entry("platform.settings", MdPref.FORM_SETTINGS),
            Map.entry("platform.navigation", MdPref.FORM_NAVIGATION),
            Map.entry("platform.modules", MdPref.FORM_MODULES),
            Map.entry("platform.announcements", "notify.announcements"),
            Map.entry("platform.files", "mf.files"),
            Map.entry("platform.search", "search"),
            Map.entry("platform.webhooks", "webhook.subscriptions"));

    /** Whether the code is {@code <area>} or {@code <area>.<name>} of well-formed segments. */
    public static boolean isWellFormed(String formCode) {
        String[] segments = formCode.split("\\.", -1);
        if (segments.length > 2) {
            return false;
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /** The area of a form code: its first segment. */
    public static String areaOf(String formCode) {
        int dot = formCode.indexOf('.');
        return dot > 0 ? formCode.substring(0, dot) : formCode;
    }

    /** The module that owns a form, or empty when the code breaks the rule or names no known area. */
    public static Optional<String> ownerOf(String formCode) {
        if (!isWellFormed(formCode)) {
            return Optional.empty();
        }
        String area = areaOf(formCode);
        if (MODULE_AREAS.contains(area)) {
            return Optional.of(area);
        }
        return Optional.ofNullable(NAMED_AREAS.get(area));
    }

    /** Whether a controller of {@code module} may guard an endpoint with the form. */
    public static boolean usableBy(String formCode, String module) {
        return ownerOf(formCode).map(owner -> owner.equals(module)).orElse(false)
                || PUBLISHED.getOrDefault(formCode, Set.of()).contains(module);
    }

    /** The current code of a form: the successor of a legacy code, otherwise the code itself. */
    public static String currentForm(String formCode) {
        return LEGACY_FORMS.getOrDefault(formCode, formCode);
    }

    /** A {@code form.action} key with a legacy form code replaced by its successor. */
    public static String currentPermission(String permission) {
        int dot = permission.lastIndexOf('.');
        if (dot <= 0) {
            return permission;
        }
        String form = permission.substring(0, dot);
        String current = LEGACY_FORMS.get(form);
        return current == null ? permission : current + permission.substring(dot);
    }
}
