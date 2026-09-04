package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.md.i18n.I18nModels.LanguageSummary;
import com.greenwhite.dwh.instance.md.service.MdI18nService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public UI-copy reads. These endpoints never expose instance or user data. */
@RestController
@RequestMapping("/api/v1/i18n")
public class MdI18nController {

    private final MdI18nService i18nService;

    public MdI18nController(MdI18nService i18nService) {
        this.i18nService = i18nService;
    }

    @GetMapping("/languages")
    public ResponseEntity<List<LanguageSummary>> listLanguages() {
        return ResponseEntity.ok(i18nService.listLanguages(true));
    }

    @GetMapping("/{lang}")
    public ResponseEntity<Map<String, String>> getDictionary(
            @PathVariable(name = "lang") String lang) {
        return ResponseEntity.ok(i18nService.effectiveDictionary(lang));
    }
}
