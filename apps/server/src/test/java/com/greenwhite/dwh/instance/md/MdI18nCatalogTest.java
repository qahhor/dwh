package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.md.service.MdI18nCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MdI18nCatalogTest {

    private static final Set<String> SUPPORTED = Set.of("ru", "uz", "en", "kk", "ky", "tg", "de", "tr");
    private static final Pattern HTML_TAG = Pattern.compile("<[/!a-zA-Z][^>]*>");

    @Test
    @DisplayName("Поставляемые словари используют только ключи русского каталога")
    void bundledCatalogsHaveOnlyKnownNonEmptyPlainTextKeys() {
        var catalog = new MdI18nCatalog(new ObjectMapper());

        assertThat(catalog.bundledCodes()).containsExactlyInAnyOrderElementsOf(SUPPORTED);
        assertThat(catalog.russianKeys()).isNotEmpty();

        for (String code : SUPPORTED) {
            var dictionary = catalog.bundled(code);
            assertThat(dictionary).as("каталог %s", code).isNotEmpty();
            assertThat(catalog.russianKeys())
                    .as("набор ключей %s", code)
                    .containsAll(dictionary.keySet());
            assertThat(dictionary)
                    .allSatisfy((key, value) -> {
                        assertThat(key).isNotBlank();
                        assertThat(value).isNotBlank();
                        assertThat(value.length()).isLessThanOrEqualTo(4000);
                        assertThat(HTML_TAG.matcher(value).find())
                                .as("HTML запрещён в %s:%s", code, key)
                                .isFalse();
                    });
        }
    }
}
