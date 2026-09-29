package com.smartup24.cms.instance.config.error;

import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ProblemMessages} from the i18n catalogs as the language editor keeps them: the entry of the key in the
 * request's language, with the {@code {placeholders}} filled.
 *
 * <p>The language is the first one of {@code Accept-Language} (the web application sends the user's) when it is an
 * active language; otherwise the system language ({@code system.default_language}); otherwise Russian. A key missing
 * in that language falls back to Russian. Rendering an error must not fail in turn: when the database cannot answer,
 * the catalogs packaged with the build are used ({@link PackagedProblemMessages}), and a key found nowhere is
 * returned as it is.
 */
@Component
public class CatalogProblemMessages implements ProblemMessages {

    static final String SYSTEM_LANGUAGE = "system.default_language";

    private static final Logger log = LoggerFactory.getLogger(CatalogProblemMessages.class);

    private final MdI18nService i18n;
    private final MdSettingService settings;

    public CatalogProblemMessages(MdI18nService i18n, MdSettingService settings) {
        this.i18n = i18n;
        this.settings = settings;
    }

    @Override
    public String render(HttpServletRequest request, String key, Map<String, ?> params) {
        return PackagedProblemMessages.fill(template(request, key), params);
    }

    private String template(HttpServletRequest request, String key) {
        try {
            String text = i18n.effectiveDictionary(language(request)).get(key);
            if (text != null) {
                return text;
            }
        } catch (RuntimeException e) {
            log.warn("Error text {} is rendered from the packaged catalog: {}", key, e.toString());
            return PackagedProblemMessages.text(PackagedProblemMessages.language(request), key);
        }
        return PackagedProblemMessages.text(PackagedProblemMessages.FALLBACK_LANGUAGE, key);
    }

    private String language(HttpServletRequest request) {
        String requested = PackagedProblemMessages.requestedLanguage(request);
        if (requested != null && i18n.isActiveLanguage(requested)) {
            return requested;
        }
        String system = settings.getInstanceSettings().get(SYSTEM_LANGUAGE);
        return i18n.isActiveLanguage(system) ? system : PackagedProblemMessages.FALLBACK_LANGUAGE;
    }
}
