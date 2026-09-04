package com.greenwhite.dwh.instance.md.i18n;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared localization domain and API contracts. */
public final class I18nModels {

    public static final String LANGUAGE_CODE_PATTERN = "^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$";

    private I18nModels() {
    }

    public record LanguageRecord(
            String code,
            String name,
            boolean builtin,
            boolean active,
            long revision,
            Long createdBy,
            Long modifiedBy,
            Instant createdAt,
            Instant modifiedAt
    ) {
    }

    public record TranslationOverride(
            String languageCode,
            String key,
            String value,
            Long modifiedBy,
            Instant modifiedAt
    ) {
    }

    public record LanguageSummary(
            String code,
            String name,
            boolean builtin,
            boolean active,
            long revision,
            int translated,
            int total,
            int coverage
    ) {
    }

    public record TranslationEntry(
            String key,
            String russianValue,
            String bundledValue,
            String overrideValue,
            String effectiveValue,
            boolean translated
    ) {
    }

    public record TranslationEditor(
            LanguageSummary language,
            List<TranslationEntry> entries
    ) {
    }

    public record CreateLanguageRequest(
            @NotBlank
            @Pattern(regexp = LANGUAGE_CODE_PATTERN)
            @Size(max = 32)
            String code,

            @NotBlank
            @Size(max = 100)
            String name,

            Map<String, String> translations
    ) {
    }

    public record UpdateTranslationsRequest(
            @Min(1)
            long expectedRevision,

            @NotNull
            @Size(max = 5000)
            Map<String, String> translations
    ) {
    }
}
