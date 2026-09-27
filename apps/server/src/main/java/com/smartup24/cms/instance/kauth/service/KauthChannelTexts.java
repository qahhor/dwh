package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import com.smartup24.cms.instance.md.service.MdUserService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Texts sent to a user's channel: one-time codes and password reset links, in the user's language.
 *
 * <p>The language is the user's own when it is an active language of the system; otherwise the system language
 * ({@code system.default_language} in the settings); otherwise Russian. A string missing in that language falls back
 * to Russian ({@link MdI18nService#effectiveDictionary}). The strings are {@code channel.<name>.subject} and
 * {@code channel.<name>.body} in the catalogs, so the language editor translates them like any other string.
 */
@Component
public class KauthChannelTexts {

    static final String SYSTEM_LANGUAGE = "system.default_language";
    static final String FALLBACK_LANGUAGE = "ru";

    private final MdI18nService i18n;
    private final MdSettingService settings;
    private final MdUserService users;

    public KauthChannelTexts(MdI18nService i18n, MdSettingService settings, MdUserService users) {
        this.i18n = i18n;
        this.settings = settings;
        this.users = users;
    }

    /**
     * @param userId the recipient
     * @param name   the message: {@code login_code}, {@code channel_verify}, {@code password_reset}
     * @param params values of the {@code {placeholders}}
     */
    public Text render(Long userId, String name, Map<String, String> params) {
        Map<String, String> strings = i18n.effectiveDictionary(language(userId));
        return new Text(
                fill(strings.getOrDefault("channel." + name + ".subject", ""), params),
                fill(strings.getOrDefault("channel." + name + ".body", ""), params));
    }

    String language(Long userId) {
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

    private static String fill(String template, Map<String, String> params) {
        String text = template;
        for (var param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", param.getValue());
        }
        return text;
    }

    public record Text(String subject, String body) {}
}
