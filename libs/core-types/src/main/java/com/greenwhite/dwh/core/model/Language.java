package com.greenwhite.dwh.core.model;

/**
 * System supported languages with ISO-639-1 codes.
 */
public enum Language {
    RU("ru", "Русский"),
    UZ("uz", "O'zbekcha"),
    EN("en", "English");

    private final String code;
    private final String title;

    Language(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public static Language fromCode(String code) {
        if (code == null || code.isBlank()) {
            return RU;
        }
        for (Language lang : values()) {
            if (lang.code.equalsIgnoreCase(code.trim())) {
                return lang;
            }
        }
        return RU; // Default fallback
    }
}
