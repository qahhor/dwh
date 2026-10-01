package com.smartup24.cms.instance.upl;

/** Constants of the upl module (file formats). */
public final class UplPref {

    public static final String FORM_SOURCES = "upl.sources";
    public static final String FORM_PACKAGES = "upl.packages";
    public static final String ACTION_VIEW = "view";
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_EDIT = "edit";
    public static final String ACTION_PUBLISH = "publish";
    public static final String ACTION_UPLOAD = "upload";
    public static final String ACTION_APPLY = "apply";
    public static final String TABLE_FORMAT_VERSIONS = "upl_format_versions";

    /** Handler code of the "parse the package file" job in the foundation queue. */
    public static final String JOB_PARSE = "upl.parse";

    /** Handler code of the "apply the package" job (plan 10/10, item 3.9): file rows streamed into raw. */
    public static final String JOB_APPLY = "upl.apply";

    private UplPref() {}
}
