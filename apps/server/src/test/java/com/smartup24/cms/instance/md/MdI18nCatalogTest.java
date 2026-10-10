package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.module.ModuleManifests;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.platform.api.PlatformVersion;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MdI18nCatalogTest {

    private static final Set<String> SUPPORTED = Set.of("ru", "uz", "en");
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
            assertThat(catalog.russianKeys()).as("набор ключей %s", code).containsAll(dictionary.keySet());
            assertThat(dictionary).allSatisfy((key, value) -> {
                assertThat(key).isNotBlank();
                assertThat(value).isNotBlank();
                assertThat(value.length()).isLessThanOrEqualTo(4000);
                assertThat(HTML_TAG.matcher(value).find())
                        .as("HTML запрещён в %s:%s", code, key)
                        .isFalse();
            });
        }
    }

    @Test
    @DisplayName("ADR-0033, 6.2: every built-in module has its name in ru, uz and en")
    void everyBuiltInModuleIsNamedInEveryLanguage() {
        var manifests = ModuleManifests.read(getClass().getClassLoader());
        var catalog = new MdI18nCatalog(new ObjectMapper(), new ModuleCatalog(PlatformVersion.current(), manifests));

        assertThat(manifests).isNotEmpty();
        for (String code : SUPPORTED) {
            for (ModuleManifest manifest : manifests) {
                assertThat(catalog.bundled(code))
                        .as("name of module %s in %s", manifest.code(), code)
                        .containsKey(manifest.titleKey());
                assertThat(catalog.bundled(code))
                        .as("description of module %s in %s", manifest.code(), code)
                        .containsKey(manifest.descriptionKey());
            }
        }
    }

    @Test
    @DisplayName("ADR-0033, 6.2: a module whose messages miss its name in one language refuses the start")
    void aModuleWithoutItsNameInALanguageRefuses() {
        var shelf = new ModuleManifest(
                "shelf",
                PlatformVersion.current(),
                PlatformVersion.current(),
                List.of(),
                List.of(),
                "com.acme.shelf.ShelfModule",
                null,
                "test-modules/shelf/i18n",
                "test:shelf");
        var modules = new ModuleCatalog(PlatformVersion.current(), List.of(shelf));

        assertThatThrownBy(() -> new MdI18nCatalog(new ObjectMapper(), modules))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Module shelf has no name in the catalog uz")
                .hasMessageContaining("shelf.module.name");
    }
}
