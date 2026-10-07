package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Texts sent to a user's channel: one-time codes and password reset links, in the user's language ({@link
 * MdUserTexts}: the user's own, else the system language, else Russian). The strings are {@code
 * channel.<name>.subject} and {@code channel.<name>.body} in the catalogs, so the language editor translates them like
 * any other string.
 */
@Component
public class KauthChannelTexts {

    private final MdUserTexts texts;

    public KauthChannelTexts(MdI18nService i18n, MdSettingService settings, MdUserService users) {
        this.texts = new MdUserTexts(i18n, settings, users);
    }

    /**
     * @param userId the recipient
     * @param name   the message: {@code login_code}, {@code channel_verify}, {@code password_reset}
     * @param params values of the {@code {placeholders}}
     */
    public Text render(Long userId, String name, Map<String, String> params) {
        Map<String, String> strings = texts.dictionary(userId);
        return new Text(
                MdUserTexts.fill(strings.getOrDefault("channel." + name + ".subject", ""), params),
                MdUserTexts.fill(strings.getOrDefault("channel." + name + ".body", ""), params));
    }

    String language(Long userId) {
        return texts.language(userId);
    }

    public record Text(String subject, String body) {}
}
