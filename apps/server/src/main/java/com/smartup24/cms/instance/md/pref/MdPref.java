package com.smartup24.cms.instance.md.pref;

/**
 * Biruni/Smartup Master Data Preferences and Constants (MdPref).
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
