package com.smartup24.cms.instance.common.entity;

import java.util.Locale;

/** What a form field holds; the screen picks the editor by it (ADR-0019, 2.5). */
public enum FormFieldType {
    TEXT, TEXTAREA, MARKDOWN, NUMBER, DATE, BOOLEAN, SELECT, REF;

    /** The name the client sees: lower case, as the list registry's types. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
