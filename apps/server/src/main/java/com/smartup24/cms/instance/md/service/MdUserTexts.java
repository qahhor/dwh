package com.smartup24.cms.instance.md.service;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A catalog text in a user's language, for what the server writes to that user itself: channel messages, in-app
 * notifications.
 *
 * <p>The language is the user's own when it is an active language of the system; otherwise the system language
 * ({@code system.default_language} in the settings); otherwise Russian. A string missing in that language falls back
 * to Russian ({@link MdI18nService#effectiveDictionary}), and the language editor translates the strings like any
 * other.
 */
@Component
public class MdUserTexts {

    static final String SYSTEM_LANGUAGE = "system.default_language";
    static final String FALLBACK_LANGUAGE = "ru";

    private final MdI18nService i18n;
    private final MdSettingService settings;
    private final MdUserService users;

    public MdUserTexts(MdI18nService i18n, MdSettingService settings, MdUserService users) {
        this.i18n = i18n;
        this.settings = settings;
        this.users = users;
    }

    /** The strings of the user's language, Russian filling the gaps. */
    public Map<String, String> dictionary(Long userId) {
        return i18n.effectiveDictionary(language(userId));
    }

    /** The text of {@code key} in the user's language with its {@code {placeholders}} filled; empty when missing. */
    public String text(Long userId, String key, Map<String, String> params) {
        return fill(dictionary(userId).getOrDefault(key, ""), params);
    }

    /** The user's language as described above. */
    public String language(Long userId) {
        String own = userId == null
                ? null
                : users.findAuthUserById(userId)
                        .map(MdUserService.AuthUser::language)
                        .orElse(null);
        if (i18n.isActiveLanguage(own)) {
            return own;
        }
        String system = settings.getInstanceSettings().get(SYSTEM_LANGUAGE);
        return i18n.isActiveLanguage(system) ? system : FALLBACK_LANGUAGE;
    }

    /** The template with every {@code {name}} replaced by its value. */
    public static String fill(String template, Map<String, String> params) {
        String text = template;
        for (var param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", param.getValue());
        }
        return text;
    }
}
