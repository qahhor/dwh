package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.i18n.I18nModels.CreateLanguageRequest;
import com.greenwhite.dwh.instance.md.i18n.I18nModels.LanguageSummary;
import com.greenwhite.dwh.instance.md.i18n.I18nModels.TranslationEditor;
import com.greenwhite.dwh.instance.md.i18n.I18nModels.UpdateTranslationsRequest;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.service.MdI18nService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/i18n/admin/languages")
public class MdI18nAdminController {

    private final MdI18nService i18nService;

    public MdI18nAdminController(MdI18nService i18nService) {
        this.i18nService = i18nService;
    }

    @GetMapping("/{code}/translations")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<TranslationEditor> getEditor(@PathVariable String code) {
        return ResponseEntity.ok(i18nService.editor(code));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<LanguageSummary> createLanguage(
            @Valid @RequestBody CreateLanguageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(i18nService.createLanguage(request, SecurityContext.getCurrentUserId()));
    }

    @PutMapping("/{code}/translations")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<LanguageSummary> updateTranslations(
            @PathVariable String code,
            @Valid @RequestBody UpdateTranslationsRequest request) {
        return ResponseEntity.ok(i18nService.updateTranslations(
                code, request, SecurityContext.getCurrentUserId()));
    }

    @GetMapping("/{code}/export")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, String>> export(@PathVariable String code) {
        String safeCode = code == null ? "ru" : code.toLowerCase().replaceAll("[^a-z0-9-]", "");
        String fileName = URLEncoder.encode(
                "smartupcms-translations-" + safeCode + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .body(i18nService.effectiveDictionary(code));
    }
}
