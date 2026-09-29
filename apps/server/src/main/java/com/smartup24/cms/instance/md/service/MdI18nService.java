package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.i18n.I18nModels.CreateLanguageRequest;
import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageRecord;
import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageSummary;
import com.smartup24.cms.instance.md.i18n.I18nModels.TranslationEditor;
import com.smartup24.cms.instance.md.i18n.I18nModels.TranslationEntry;
import com.smartup24.cms.instance.md.i18n.I18nModels.UpdateTranslationsRequest;
import com.smartup24.cms.instance.md.repository.MdI18nRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MdI18nService {

    private static final String RUSSIAN = "ru";
    private static final Pattern LANGUAGE_CODE = Pattern.compile("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$");

    private final MdI18nRepository repository;
    private final MdI18nCatalog catalog;
    private final AuditLogService auditLogService;
    private final Map<String, CachedDictionary> cache = new ConcurrentHashMap<>();

    public MdI18nService(MdI18nRepository repository, MdI18nCatalog catalog, AuditLogService auditLogService) {
        this.repository = repository;
        this.catalog = catalog;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<LanguageSummary> listLanguages(boolean activeOnly) {
        Map<String, Map<String, String>> overrides = repository.findAllOverrides();
        return repository.findLanguages(activeOnly).stream()
                .map(language -> summary(language, overrides.getOrDefault(language.code(), Map.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, String> effectiveDictionary(String requestedCode) {
        String code = normalizeCodeOrRussian(requestedCode);
        LanguageRecord language =
                repository.findLanguage(code).filter(LanguageRecord::active).orElseGet(this::russianLanguage);
        LanguageRecord russian = RUSSIAN.equals(language.code()) ? language : russianLanguage();

        CachedDictionary cached = cache.get(language.code());
        if (cached != null
                && cached.languageRevision() == language.revision()
                && cached.russianRevision() == russian.revision()) {
            return cached.values();
        }

        Map<String, String> russianValues = mergedRussian();
        Map<String, String> merged;
        if (RUSSIAN.equals(language.code())) {
            merged = russianValues;
        } else {
            LinkedHashMap<String, String> values = new LinkedHashMap<>(russianValues);
            values.putAll(catalog.bundled(language.code()));
            values.putAll(repository.findOverrides(language.code()));
            merged = Collections.unmodifiableMap(values);
        }

        cache.put(language.code(), new CachedDictionary(language.revision(), russian.revision(), merged));
        return merged;
    }

    /** Whether the code names an active language; {@code false} for null or a malformed code. */
    @Transactional(readOnly = true)
    public boolean isActiveLanguage(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String normalized = code.trim().toLowerCase();
        return LANGUAGE_CODE.matcher(normalized).matches()
                && repository
                        .findLanguage(normalized)
                        .filter(LanguageRecord::active)
                        .isPresent();
    }

    @Transactional(readOnly = true)
    public String requireActiveLanguageCode(String requestedCode) {
        String code = normalizeRequiredCode(requestedCode);
        if (repository.findLanguage(code).filter(LanguageRecord::active).isEmpty()) {
            throw ApiException.notFound(
                    ErrorCode.I18N_LANGUAGE_NOT_FOUND, "error.md.language_not_found_or_inactive", Map.of("code", code));
        }
        return code;
    }

    @Transactional(readOnly = true)
    public TranslationEditor editor(String requestedCode) {
        String code = normalizeRequiredCode(requestedCode);
        LanguageRecord language = repository.findLanguage(code).orElseThrow(() -> languageNotFound(code));

        Map<String, String> russian = mergedRussian();
        Map<String, String> bundled = catalog.bundled(code);
        Map<String, String> overrides = repository.findOverrides(code);

        List<TranslationEntry> entries = catalog.russianKeys().stream()
                .map(key -> {
                    String russianValue = russian.getOrDefault(key, key);
                    String bundledValue = bundled.get(key);
                    String overrideValue = overrides.get(key);
                    String effective =
                            overrideValue != null ? overrideValue : bundledValue != null ? bundledValue : russianValue;
                    boolean translated = RUSSIAN.equals(code) || overrideValue != null || bundledValue != null;
                    return new TranslationEntry(key, russianValue, bundledValue, overrideValue, effective, translated);
                })
                .toList();

        return new TranslationEditor(summary(language, overrides), entries);
    }

    @Transactional
    public LanguageSummary createLanguage(CreateLanguageRequest request, Long userId) {
        if (request == null) {
            throw ApiException.badRequest(ErrorCode.I18N_LANGUAGE_INVALID, "error.md.language_params_required");
        }
        String code = normalizeRequiredCode(request.code());
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest(ErrorCode.I18N_LANGUAGE_INVALID, "error.md.language_name_invalid");
        }
        Map<String, String> overrides = validatedOverrides(request.translations());
        Optional<LanguageRecord> existing = repository.findLanguage(code);
        LanguageRecord created;
        Map<String, String> previousOverrides = Map.of();
        if (existing.isPresent()) {
            // A switched-off custom language (German after V122, or one an administrator switched off) comes back
            // on with the overrides the installation made; an active or built-in one is a conflict.
            created = repository
                    .reactivateLanguage(code, name, userId)
                    .orElseThrow(() -> ApiException.conflict(
                            ErrorCode.I18N_LANGUAGE_EXISTS, "error.md.language_exists", Map.of("code", code)));
            previousOverrides = repository.findOverrides(code);
            auditLogService.logChange(
                    "md_i18n_languages",
                    code,
                    "U",
                    List.of("name", "is_active"),
                    Map.of("name", existing.get().name(), "is_active", false),
                    Map.of("name", name, "is_active", true));
        } else {
            created = repository.insertLanguage(code, name, userId);
            auditLogService.logChange(
                    "md_i18n_languages",
                    code,
                    "I",
                    List.of("code", "name", "is_active"),
                    Map.of(),
                    Map.of("code", code, "name", name, "is_active", true));
        }
        long revision = created.revision();
        Map<String, String> finalOverrides = previousOverrides;
        if (!overrides.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(previousOverrides);
            merged.putAll(overrides);
            revision = repository.replaceOverrides(code, merged, revision, userId);
            finalOverrides = merged;
        }
        auditTranslationChanges(code, previousOverrides, finalOverrides);
        cache.remove(code);

        LanguageRecord saved = new LanguageRecord(
                created.code(),
                created.name(),
                created.builtin(),
                created.active(),
                revision,
                created.createdBy(),
                userId,
                created.createdAt(),
                created.modifiedAt());
        return summary(saved, overrides);
    }

    @Transactional
    public LanguageSummary updateTranslations(String requestedCode, UpdateTranslationsRequest request, Long userId) {
        String code = normalizeRequiredCode(requestedCode);
        if (request == null) {
            throw ApiException.badRequest(ErrorCode.I18N_TRANSLATION_INVALID, "error.md.translations_required");
        }
        LanguageRecord language = repository.findLanguage(code).orElseThrow(() -> languageNotFound(code));
        Map<String, String> oldOverrides = repository.findOverrides(code);
        Map<String, String> newOverrides = validatedOverrides(request.translations());

        long revision = repository.replaceOverrides(code, newOverrides, request.expectedRevision(), userId);
        auditTranslationChanges(code, oldOverrides, newOverrides);

        if (RUSSIAN.equals(code)) {
            cache.clear();
        } else {
            cache.remove(code);
        }

        LanguageRecord saved = new LanguageRecord(
                language.code(),
                language.name(),
                language.builtin(),
                language.active(),
                revision,
                language.createdBy(),
                userId,
                language.createdAt(),
                language.modifiedAt());
        return summary(saved, newOverrides);
    }

    private Map<String, String> mergedRussian() {
        LanguageRecord russian = russianLanguage();
        CachedDictionary cached = cache.get(RUSSIAN);
        if (cached != null && cached.languageRevision() == russian.revision()) {
            return cached.values();
        }

        LinkedHashMap<String, String> values = new LinkedHashMap<>(catalog.bundled(RUSSIAN));
        values.putAll(repository.findOverrides(RUSSIAN));
        Map<String, String> result = Collections.unmodifiableMap(values);
        cache.put(RUSSIAN, new CachedDictionary(russian.revision(), russian.revision(), result));
        return result;
    }

    private LanguageRecord russianLanguage() {
        return repository
                .findLanguage(RUSSIAN)
                .orElseThrow(() -> new IllegalStateException("Русский язык не зарегистрирован"));
    }

    private LanguageSummary summary(LanguageRecord language, Map<String, String> overrides) {
        int total = catalog.russianKeys().size();
        int translated;
        if (RUSSIAN.equals(language.code())) {
            translated = total;
        } else {
            translated = 0;
            Map<String, String> bundled = catalog.bundled(language.code());
            for (String key : catalog.russianKeys()) {
                if (overrides.containsKey(key) || bundled.containsKey(key)) {
                    translated++;
                }
            }
        }
        int coverage = total == 0 ? 100 : (int) Math.round(translated * 100.0 / total);
        return new LanguageSummary(
                language.code(),
                language.name(),
                language.builtin(),
                language.active(),
                language.revision(),
                translated,
                total,
                coverage);
    }

    private Map<String, String> validatedOverrides(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        if (requested.size() > 5000) {
            throw ApiException.badRequest(ErrorCode.I18N_TRANSLATION_INVALID, "error.md.translations_too_many");
        }

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        requested.forEach((key, value) -> {
            if (key == null || !catalog.russianKeys().contains(key)) {
                throw ApiException.badRequest(
                        ErrorCode.I18N_TRANSLATION_INVALID,
                        "error.md.translation_key_unknown",
                        Map.of("key", String.valueOf(key)));
            }
            if (value == null || value.isBlank()) {
                return;
            }
            if (value.length() > 4000) {
                throw ApiException.badRequest(
                        ErrorCode.I18N_TRANSLATION_INVALID, "error.md.translation_too_long", Map.of("key", key));
            }
            normalized.put(key, value);
        });
        return Collections.unmodifiableMap(normalized);
    }

    private void auditTranslationChanges(String code, Map<String, String> oldValues, Map<String, String> newValues) {
        catalog.russianKeys().forEach(key -> {
            String oldValue = oldValues.get(key);
            String newValue = newValues.get(key);
            if (Objects.equals(oldValue, newValue)) {
                return;
            }
            String event = oldValue == null ? "I" : newValue == null ? "D" : "U";
            auditLogService.logChange(
                    "md_i18n_translation_overrides",
                    code + ":" + key,
                    event,
                    List.of("value"),
                    oldValue == null ? Map.of() : Map.of("value", oldValue),
                    newValue == null ? Map.of() : Map.of("value", newValue));
        });
    }

    private String normalizeCodeOrRussian(String code) {
        if (code == null || code.isBlank()) {
            return RUSSIAN;
        }
        String normalized = code.trim().toLowerCase();
        return LANGUAGE_CODE.matcher(normalized).matches() ? normalized : RUSSIAN;
    }

    private String normalizeRequiredCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        if (!LANGUAGE_CODE.matcher(normalized).matches()) {
            throw ApiException.badRequest(
                    ErrorCode.I18N_LANGUAGE_INVALID,
                    "error.md.language_code_invalid",
                    Map.of("code", String.valueOf(code)));
        }
        return normalized;
    }

    private static ApiException languageNotFound(String code) {
        return ApiException.notFound(
                ErrorCode.I18N_LANGUAGE_NOT_FOUND, "error.md.language_not_found", Map.of("code", code));
    }

    private record CachedDictionary(long languageRevision, long russianRevision, Map<String, String> values) {}
}
