package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.md.api.MdPref;
import com.smartup24.cms.instance.md.i18n.I18nModels.CreateLanguageRequest;
import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageSummary;
import com.smartup24.cms.instance.md.i18n.I18nModels.TranslationEditor;
import com.smartup24.cms.instance.md.i18n.I18nModels.UpdateTranslationsRequest;
import com.smartup24.cms.instance.md.service.MdI18nService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/i18n/admin/languages")
public class MdI18nAdminController {

    private final MdI18nService i18nService;

    public MdI18nAdminController(MdI18nService i18nService) {
        this.i18nService = i18nService;
    }

    @Operation(
            summary = "Get the translation editor",
            description = "The keys and texts of an interface language, for the translation editor.")
    @GetMapping("/{code}/translations")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<TranslationEditor> getEditor(@PathVariable String code) {
        return ResponseEntity.ok(i18nService.editor(code));
    }

    @Operation(summary = "Add an interface language", description = "Adds a language to the interface.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<LanguageSummary> createLanguage(@Valid @RequestBody CreateLanguageRequest request) {
        LanguageSummary created = i18nService.createLanguage(request, SecurityContext.getCurrentUserId());
        return Created.at("/api/v1/i18n/admin/languages/{code}/translations", created.code(), created);
    }

    @Operation(summary = "Update translations", description = "Saves the edited texts of an interface language.")
    @PutMapping("/{code}/translations")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<LanguageSummary> updateTranslations(
            @PathVariable String code, @Valid @RequestBody UpdateTranslationsRequest request) {
        return ResponseEntity.ok(i18nService.updateTranslations(code, request, SecurityContext.getCurrentUserId()));
    }

    @Operation(
            summary = "Export a language",
            description = "The texts of an interface language as one key-to-text map.")
    @GetMapping("/{code}/export")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, String>> export(@PathVariable String code) {
        String safeCode = code == null ? "ru" : code.toLowerCase().replaceAll("[^a-z0-9-]", "");
        String fileName = URLEncoder.encode("smartupcms-translations-" + safeCode + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .body(i18nService.effectiveDictionary(code));
    }
}
