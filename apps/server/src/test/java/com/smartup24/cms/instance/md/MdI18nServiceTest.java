package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.i18n.I18nModels.CreateLanguageRequest;
import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageRecord;
import com.smartup24.cms.instance.md.i18n.I18nModels.UpdateTranslationsRequest;
import com.smartup24.cms.instance.md.repository.MdI18nRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdI18nService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class MdI18nServiceTest {

    private MdI18nRepository repository;
    private AuditLogService audit;
    private MdI18nCatalog catalog;
    private MdI18nService service;

    @BeforeEach
    void setup() {
        repository = Mockito.mock(MdI18nRepository.class);
        audit = Mockito.mock(AuditLogService.class);
        catalog = new MdI18nCatalog(new ObjectMapper());
        service = new MdI18nService(repository, catalog, audit);

        when(repository.findLanguage("ru")).thenReturn(Optional.of(language("ru", true, true, 2)));
        when(repository.findOverrides("ru")).thenReturn(Map.of("common.save", "Сберечь"));
    }

    @Test
    @DisplayName("Эффективный словарь соблюдает порядок override → bundle → русский")
    void mergesTargetAndRussianFallbackInOrder() {
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en", true, true, 4)));
        when(repository.findOverrides("en")).thenReturn(Map.of("common.save", "Keep"));
        when(repository.findLanguage("fr")).thenReturn(Optional.of(language("fr", false, true, 1)));
        when(repository.findOverrides("fr")).thenReturn(Map.of("nav.tasks", "Tâches"));

        assertThat(service.effectiveDictionary("en"))
                .containsEntry("common.save", "Keep")
                .containsEntry("nav.tasks", "Tasks");
        assertThat(service.effectiveDictionary("fr"))
                .containsEntry("nav.tasks", "Tâches")
                .containsEntry("common.save", "Сберечь")
                .containsEntry("common.cancel", "Отмена");
    }

    @Test
    @DisplayName("Неизвестный и отключённый язык безопасно возвращают русский")
    void unknownAndInactiveLanguagesFallBackToRussian() {
        when(repository.findLanguage("xx")).thenReturn(Optional.empty());
        when(repository.findLanguage("fr")).thenReturn(Optional.of(language("fr", false, false, 1)));

        assertThat(service.effectiveDictionary("xx")).containsEntry("common.save", "Сберечь");
        assertThat(service.effectiveDictionary("fr")).containsEntry("common.save", "Сберечь");
    }

    @Test
    @DisplayName("Пользовательский выбор принимает только зарегистрированный активный язык")
    void requiresRegisteredActiveLanguageForUserPreference() {
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en", true, true, 4)));
        when(repository.findLanguage("fr")).thenReturn(Optional.of(language("fr", false, false, 1)));

        assertThat(service.requireActiveLanguageCode(" EN ")).isEqualTo("en");
        assertThatThrownBy(() -> service.requireActiveLanguageCode("fr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("не найден или отключён");
    }

    @Test
    @DisplayName("Редактор считает только реально переведённые строки пользовательского языка")
    void editorReportsCustomLanguageCoverage() {
        when(repository.findLanguage("fr")).thenReturn(Optional.of(language("fr", false, true, 3)));
        when(repository.findOverrides("fr"))
                .thenReturn(Map.of(
                        "nav.tasks", "Tâches",
                        "common.save", "Enregistrer"));

        var editor = service.editor("fr");

        assertThat(editor.language().translated()).isEqualTo(2);
        assertThat(editor.language().total()).isEqualTo(catalog.russianKeys().size());
        assertThat(editor.entries())
                .filteredOn(entry -> entry.key().equals("common.cancel"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.translated()).isFalse();
                    assertThat(entry.effectiveValue()).isEqualTo("Отмена");
                });
    }

    @Test
    @DisplayName("Неизвестный ключ отклоняется до записи")
    void rejectsUnknownTranslationKey() {
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en", true, true, 1)));

        assertThatThrownBy(() -> service.updateTranslations(
                        "en", new UpdateTranslationsRequest(1, Map.of("typo.unknown", "Wert")), 7L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Неизвестный ключ")
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.I18N_TRANSLATION_INVALID));

        verify(repository, never()).replaceOverrides(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Сохранение сбрасывает кэш и оставляет аудит")
    void updateInvalidatesCacheAndAuditsChanges() {
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en", true, true, 1)));
        when(repository.findOverrides("en"))
                .thenReturn(Map.of())
                .thenReturn(Map.of())
                .thenReturn(Map.of("common.save", "Keep"));
        when(repository.replaceOverrides(eq("en"), any(), eq(1L), eq(7L))).thenReturn(2L);

        assertThat(service.effectiveDictionary("en")).containsEntry("common.save", "Save");
        service.updateTranslations("en", new UpdateTranslationsRequest(1, Map.of("common.save", "Keep")), 7L);
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en", true, true, 2)));

        assertThat(service.effectiveDictionary("en")).containsEntry("common.save", "Keep");
        verify(audit)
                .logChange(eq("md_i18n_translation_overrides"), eq("en:common.save"), eq("I"), any(), any(), any());
    }

    @Test
    @DisplayName("Новый язык нормализуется и сохраняет начальный пакет")
    void createsNormalizedCustomLanguage() {
        when(repository.findLanguage("fr-ca")).thenReturn(Optional.empty());
        when(repository.insertLanguage("fr-ca", "Français (Canada)", 7L)).thenReturn(language("fr-ca", false, true, 1));
        when(repository.replaceOverrides(eq("fr-ca"), any(), eq(1L), eq(7L))).thenReturn(2L);

        var result = service.createLanguage(
                new CreateLanguageRequest(" FR-CA ", " Français (Canada) ", Map.of("common.save", "Enregistrer")), 7L);

        assertThat(result.code()).isEqualTo("fr-ca");
        assertThat(result.revision()).isEqualTo(2);
        verify(repository).replaceOverrides("fr-ca", Map.of("common.save", "Enregistrer"), 1L, 7L);
    }

    private LanguageRecord language(String code, boolean builtin, boolean active, long revision) {
        return new LanguageRecord(
                code,
                code.toUpperCase(),
                builtin,
                active,
                revision,
                null,
                null,
                Instant.parse("2026-09-04T00:00:00Z"),
                Instant.parse("2026-09-04T00:00:00Z"));
    }
}
