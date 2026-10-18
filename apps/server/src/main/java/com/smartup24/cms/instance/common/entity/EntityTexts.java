package com.smartup24.cms.instance.common.entity;

import java.util.Map;

/**
 * The dictionary of the language of the work in progress ({@code common.web.RequestLanguage}): the words of the
 * dictionary keys of choices and of the live forms' own labels, read where the server writes names rather than keys —
 * a report's group labels and its XLSX (ADR-0036). {@code common} knows no module, so md implements it.
 */
public interface EntityTexts {
    /** The entries of {@code language}, Russian for a key it lacks; empty when no dictionary answers. */
    Map<String, String> dictionary(String language);
}
