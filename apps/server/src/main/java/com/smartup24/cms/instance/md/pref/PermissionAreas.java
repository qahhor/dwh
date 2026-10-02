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
 * a module whose code does not fit one segment ({@code ms.task}). A controller guards its endpoints with forms of its
 * own module; another module's form only when the owner publishes it to that module ({@link #PUBLISHED}), the way ADR-0026 publishes read views. {@code PermissionCodesTest}
 * checks every {@code @RequiresPermission} against both tables.
 */
public final class PermissionAreas {

    private PermissionAreas() {}

    /** One lower-case segment: letters, digits and underscores, starting with a letter. */
    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9_]*");

    /** Areas that are business module codes themselves. */
    private static final Set<String> MODULE_AREAS = Set.of(
            "analytics",
            "audit",
            "example",
            "jobs",
            "kauth",
            "md",
            "mf",
            "report",
            "search",
            "units",
            "upl",
            "warehouse",
            "webhook");

    /** Named areas and their owning modules. */
    private static final Map<String, String> NAMED_AREAS = Map.of(
            "tasks", "ms.task",
            "notes", "ms.note",
            "notify", "ms.notify");

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
}
