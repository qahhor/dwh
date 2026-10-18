package com.smartup24.cms.instance.common.formula;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The text functions of formulas (ADR-0042): lengths count characters (code points), case follows the Unicode default
 * mapping without a locale, {@code trim} removes spaces, tabs and line breaks, and no text grows beyond
 * {@link Formula#MAX_TEXT} characters ({@code text_too_long}).
 */
final class FormulaTexts {
    private FormulaTexts() {}

    /** The text when it is within the bound of a formula's texts. */
    static String bounded(String text) {
        if (text.codePointCount(0, text.length()) > Formula.MAX_TEXT) throw new FormulaFailure("text_too_long");
        return text;
    }

    /** The number of characters; an empty value has none. */
    static int length(@Nullable Object text) {
        return text == null ? 0 : ((String) text).codePointCount(0, ((String) text).length());
    }

    static @Nullable String upper(@Nullable Object text) {
        return text == null ? null : bounded(((String) text).toUpperCase(Locale.ROOT));
    }

    static @Nullable String lower(@Nullable Object text) {
        return text == null ? null : bounded(((String) text).toLowerCase(Locale.ROOT));
    }

    static @Nullable String trim(@Nullable Object text) {
        return text == null ? null : FormulaValues.trim((String) text);
    }

    /** Whether {@code text} holds {@code part}, case-sensitively; false when either is empty. */
    static boolean contains(@Nullable Object text, @Nullable Object part) {
        return text != null && part != null && ((String) text).contains((String) part);
    }
}
