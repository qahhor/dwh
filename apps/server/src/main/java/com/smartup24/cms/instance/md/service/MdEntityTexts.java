package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityTexts;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The effective dictionary of a language as the language editor keeps it, for the words the server writes. */
@Component
public class MdEntityTexts implements EntityTexts {
    private final MdI18nService i18n;

    public MdEntityTexts(MdI18nService i18n) {
        this.i18n = i18n;
    }

    @Override
    public Map<String, String> dictionary(String language) {
        return i18n.effectiveDictionary(language);
    }
}
