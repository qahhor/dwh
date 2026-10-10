package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class MdI18nCatalog {

    /** Languages with a catalog in the build; others are an administrator's own, translated in the language editor. */
    private static final List<String> BUNDLED_CODES = List.of("ru", "uz", "en");

    private final Map<String, Map<String, String>> dictionaries;
    private final Set<String> russianKeys;

    /** The catalogs of the build alone, without the messages of modules outside the monorepo. */
    public MdI18nCatalog(ObjectMapper objectMapper) {
        this(objectMapper, List.of(), MdI18nCatalog.class.getClassLoader());
    }

    /**
     * The catalogs of the build with the messages of every module that brings its own (ADR-0033, 6.5): a module's key
     * that the platform or another module has already refuses the start.
     */
    @Autowired
    public MdI18nCatalog(ObjectMapper objectMapper, ModuleCatalog modules) {
        this(objectMapper, modules.manifests(), modules.classLoader());
    }

    private MdI18nCatalog(ObjectMapper objectMapper, List<ModuleManifest> modules, ClassLoader loader) {
        ObjectMapper strictMapper = objectMapper
                .rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String code : BUNDLED_CODES) {
            Map<String, String> dictionary = new LinkedHashMap<>(load(
                    strictMapper,
                    new ClassPathResource("i18n/" + code + ".json", MdI18nCatalog.class.getClassLoader())));
            for (ModuleManifest module : modules) {
                addModule(strictMapper, dictionary, module, code, loader);
            }
            loaded.put(code, Collections.unmodifiableMap(dictionary));
        }

        Map<String, String> russian = loaded.get("ru");
        if (russian == null || russian.isEmpty()) {
            throw new IllegalStateException("The Russian catalog is missing or empty");
        }

        Set<String> canonicalKeys = new LinkedHashSet<>(russian.keySet());
        for (var language : loaded.entrySet()) {
            validate(language.getKey(), language.getValue(), canonicalKeys);
            requireModuleNames(language.getKey(), language.getValue(), modules);
        }

        dictionaries = Collections.unmodifiableMap(loaded);
        russianKeys = Collections.unmodifiableSet(canonicalKeys);
    }

    public Set<String> russianKeys() {
        return russianKeys;
    }

    public Map<String, String> bundled(String code) {
        if (code == null) {
            return Map.of();
        }
        return dictionaries.getOrDefault(code.toLowerCase(), Map.of());
    }

    public boolean isBundled(String code) {
        return code != null && dictionaries.containsKey(code.toLowerCase());
    }

    public Set<String> bundledCodes() {
        return dictionaries.keySet();
    }

    private static Map<String, String> load(ObjectMapper objectMapper, ClassPathResource resource) {
        try (InputStream input = resource.getInputStream()) {
            Map<String, String> values =
                    objectMapper.readValue(input, new TypeReference<LinkedHashMap<String, String>>() {});
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        } catch (IOException exception) {
            throw new IllegalStateException("The catalog " + resource.getPath() + " is unreadable", exception);
        }
    }

    /** The module's keys in one language, when it brings that language; a key taken already refuses. */
    private static void addModule(
            ObjectMapper mapper,
            Map<String, String> dictionary,
            ModuleManifest module,
            String language,
            ClassLoader loader) {
        String folder = module.messages();
        if (folder == null) return;
        ClassPathResource resource = new ClassPathResource(folder + "/" + language + ".json", loader);
        if (!resource.exists()) return;
        load(mapper, resource).forEach((key, value) -> {
            if (dictionary.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        "Module " + module.code() + " brings the key " + key + " the catalog " + language + " has");
            }
        });
    }

    /** Every module is named in every bundled language (ADR-0033, 6.2): a module without its name refuses the start. */
    private static void requireModuleNames(
            String language, Map<String, String> dictionary, List<ModuleManifest> modules) {
        for (ModuleManifest module : modules) {
            if (!dictionary.containsKey(module.titleKey())) {
                throw new IllegalStateException("Module " + module.code() + " has no name in the catalog " + language
                        + ": add the key " + module.titleKey() + " to its messages");
            }
        }
    }

    private void validate(String code, Map<String, String> dictionary, Set<String> canonicalKeys) {
        if (!"ru".equals(code) && !canonicalKeys.containsAll(dictionary.keySet())) {
            Set<String> unknown = new LinkedHashSet<>(dictionary.keySet());
            unknown.removeAll(canonicalKeys);
            throw new IllegalStateException("Catalog " + code + " has unknown keys: " + unknown);
        }
        dictionary.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalStateException("Empty translation in catalog " + code + ":" + key);
            }
            if (value.length() > 4000) {
                throw new IllegalStateException("Translation too long in catalog " + code + ":" + key);
            }
            if (value.matches(".*<[/!a-zA-Z][^>]*>.*")) {
                throw new IllegalStateException("HTML is not allowed in catalog " + code + ":" + key);
            }
        });
    }
}
