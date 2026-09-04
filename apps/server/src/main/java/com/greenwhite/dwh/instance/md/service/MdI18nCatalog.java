package com.greenwhite.dwh.instance.md.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MdI18nCatalog {

    private static final List<String> BUNDLED_CODES = List.of(
            "ru", "uz", "en", "kk", "ky", "tg", "de", "tr");

    private final Map<String, Map<String, String>> dictionaries;
    private final Set<String> russianKeys;

    public MdI18nCatalog(ObjectMapper objectMapper) {
        ObjectMapper strictMapper = objectMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String code : BUNDLED_CODES) {
            loaded.put(code, load(strictMapper, code));
        }

        Map<String, String> russian = loaded.get("ru");
        if (russian == null || russian.isEmpty()) {
            throw new IllegalStateException("Русский каталог локализации отсутствует или пуст");
        }

        Set<String> canonicalKeys = new LinkedHashSet<>(russian.keySet());
        for (var language : loaded.entrySet()) {
            validate(language.getKey(), language.getValue(), canonicalKeys);
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

    private Map<String, String> load(ObjectMapper objectMapper, String code) {
        var resource = new ClassPathResource("i18n/" + code + ".json");
        try (InputStream input = resource.getInputStream()) {
            Map<String, String> values = objectMapper.readValue(
                    input, new TypeReference<LinkedHashMap<String, String>>() { });
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить каталог i18n/" + code + ".json", exception);
        }
    }

    private void validate(String code, Map<String, String> dictionary, Set<String> canonicalKeys) {
        if (!"ru".equals(code) && !canonicalKeys.containsAll(dictionary.keySet())) {
            Set<String> unknown = new LinkedHashSet<>(dictionary.keySet());
            unknown.removeAll(canonicalKeys);
            throw new IllegalStateException(
                    "Каталог " + code + " содержит неизвестные ключи: " + unknown);
        }
        dictionary.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalStateException("Пустой перевод в каталоге " + code + ":" + key);
            }
            if (value.length() > 4000) {
                throw new IllegalStateException("Слишком длинный перевод в каталоге " + code + ":" + key);
            }
            if (value.matches(".*<[/!a-zA-Z][^>]*>.*")) {
                throw new IllegalStateException("HTML запрещён в каталоге " + code + ":" + key);
            }
        });
    }
}
