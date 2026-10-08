package com.smartup24.cms.instance.md.api;

/**
 * Master data constants other modules share: role codes, user states and form codes. They are published in the api
 * package because a compile-time constant is inlined into its user's bytecode, so only the source shows the
 * dependency; other modules read them only from here (plan 10/10, item 1.3).
 */
public final class MdPref {
    private MdPref() {}

    public static final String MODULE_CODE = "md";

    // System Role Pcodes
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MANAGER = "manager";
    public static final String ROLE_AUDITOR = "auditor";
    public static final String ROLE_USER = "user";

    // System States
    public static final String STATE_ACTIVE = "A";
    public static final String STATE_PASSIVE = "P";

    // Forms: <area>.<entity-or-screen>, the area of md is md (ADR-0028)
    public static final String FORM_USERS = "md.users";
    public static final String FORM_PROFILE = "md.profile";
    public static final String FORM_ROLES = "md.roles";
    public static final String FORM_ASSIGNMENTS = "md.assignments";
    public static final String FORM_CUSTOM_FIELDS = "md.custom_fields";
    public static final String FORM_SETTINGS = "md.settings";
    public static final String FORM_ORG_UNITS = "md.org_units";
    public static final String FORM_NAVIGATION = "md.navigation";
    public static final String FORM_MODULES = "md.modules";
}
