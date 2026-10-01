package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageSummary;
import com.smartup24.cms.instance.md.service.MdI18nService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public UI-copy reads. These endpoints never expose instance or user data. */
@RestController
@RequestMapping("/api/v1/i18n")
public class MdI18nController {

    private final MdI18nService i18nService;

    public MdI18nController(MdI18nService i18nService) {
        this.i18nService = i18nService;
    }

    @Operation(summary = "List interface languages", description = "The languages the interface is available in.")
    @GetMapping("/languages")
    public ResponseEntity<List<LanguageSummary>> listLanguages() {
        return ResponseEntity.ok(i18nService.listLanguages(true));
    }

    @Operation(summary = "Get a dictionary", description = "The interface texts of one language, key to text.")
    @GetMapping("/{lang}")
    public ResponseEntity<Map<String, String>> getDictionary(@PathVariable(name = "lang") String lang) {
        return ResponseEntity.ok(i18nService.effectiveDictionary(lang));
    }
}
