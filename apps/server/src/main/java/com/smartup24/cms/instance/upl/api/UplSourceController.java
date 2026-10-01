package com.smartup24.cms.instance.upl.api;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.CreateDraftRequest;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.FormatDraftRequest;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.FormatVersionResponse;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.PublishRequest;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.SourceItem;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.SourceRequest;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.SourceResponse;
import com.smartup24.cms.instance.upl.api.UplSourceDtos.VersionItem;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.format.UplTemplateBuilder;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API анкеты файла: источники и версии формата (контракт И3). */
@RestController
@RequestMapping("/api/v1/upl/sources")
public class UplSourceController {

    private final UplSourceService service;
    private final UplTemplateBuilder templates;
    private final MdI18nService i18n;

    public UplSourceController(UplSourceService service, UplTemplateBuilder templates, MdI18nService i18n) {
        this.service = service;
        this.templates = templates;
        this.i18n = i18n;
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    @GetMapping
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<KeysetPage<SourceItem>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q) {
        KeysetPage<SourceSummary> page = service.listSources(limit, cursor, filter, sort, q);
        return ResponseEntity.ok(page.map(SourceItem::of));
    }

    @PostMapping
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<SourceResponse> create(@Valid @RequestBody SourceRequest request) {
        SourceResponse created = SourceResponse.of(service.createSource(request.toData(), userId()));
        return Created.at("/api/v1/upl/sources/{id}", created.id(), created);
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<SourceResponse> get(@PathVariable long id) {
        return ResponseEntity.ok(SourceResponse.of(service.getSource(id)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_EDIT)
    public ResponseEntity<SourceResponse> update(@PathVariable long id, @Valid @RequestBody SourceRequest request) {
        if (request.lockVersion() == null) {
            throw ApiException.validation(
                    "error.validation_failed",
                    List.of(FieldErrorItem.keyed("lockVersion", "REQUIRED", "error.field.lock_version_required")));
        }
        var view = service.updateSource(id, request.lockVersion(), request.toData(), userId());
        return ResponseEntity.ok(SourceResponse.of(view));
    }

    /**
     * The versions of a source. With {@code at} the same path answers the one version in force that day instead: two
     * typed handlers told apart by the parameter (plan 10/10, item 3.2), described as one operation whose answer is
     * one of the two shapes.
     */
    @GetMapping(value = "/{id}/format-versions", params = "!at")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<List<VersionItem>> versions(@PathVariable long id) {
        return ResponseEntity.ok(
                service.listVersions(id).stream().map(VersionItem::of).toList());
    }

    /**
     * The format version in force on the day {@code at}. The parameter is optional in the description because the
     * operation it shares with the list is; here it is present by the mapping, and an empty value is a 400.
     */
    @GetMapping(value = "/{id}/format-versions", params = "at")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    @Operation(operationId = "versions")
    public ResponseEntity<FormatVersionResponse> versionAt(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        if (at == null) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.request_param_missing", Map.of("name", "at"));
        }
        return ResponseEntity.ok(FormatVersionResponse.of(service.versionAt(id, at)));
    }

    @PostMapping("/{id}/format-versions")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<FormatVersionResponse> createDraft(
            @PathVariable long id, @Valid @RequestBody(required = false) CreateDraftRequest request) {
        Integer copyFrom = request == null ? null : request.copyFrom();
        var draft = service.createDraft(id, copyFrom, userId());
        FormatVersionResponse created = FormatVersionResponse.of(draft);
        return Created.at(
                "/api/v1/upl/sources/{id}/format-versions/{v}", new Object[] {id, created.version()}, created);
    }

    @GetMapping("/{id}/format-versions/{v}")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<FormatVersionResponse> getVersion(@PathVariable long id, @PathVariable int v) {
        return ResponseEntity.ok(FormatVersionResponse.of(service.getVersion(id, v)));
    }

    /**
     * The file a supplier fills in for this format version (roadmap item 20): an instruction sheet and
     * the data sheets with headers, notes and input checks, in the language asked for (Russian by default).
     */
    @GetMapping("/{id}/format-versions/{v}/template")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<byte[]> template(
            @PathVariable long id, @PathVariable int v, @RequestParam(required = false) String lang) {
        var source = service.getSource(id).source();
        var version = service.getVersion(id, v);
        Map<String, String> dictionary = i18n.effectiveDictionary(lang);
        UplTemplateBuilder.TemplateFile file =
                templates.build(source, version, (key, params) -> fill(dictionary.getOrDefault(key, key), params));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.content());
    }

    /** Puts {@code {name}} parameters into a dictionary text, as the web client does. */
    private static String fill(String template, Map<String, Object> params) {
        String result = template;
        for (var entry : params.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    @PutMapping("/{id}/format-versions/{v}")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_EDIT)
    public ResponseEntity<FormatVersionResponse> replaceDraft(
            @PathVariable long id, @PathVariable int v, @Valid @RequestBody FormatDraftRequest request) {
        var draft = service.replaceDraft(id, v, request.lockVersion(), request.toData(), userId());
        return ResponseEntity.ok(FormatVersionResponse.of(draft));
    }

    @PostMapping("/{id}/format-versions/{v}/publish")
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_PUBLISH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> publish(
            @PathVariable long id, @PathVariable int v, @Valid @RequestBody PublishRequest request) {
        service.publish(id, v, request.validFrom(), userId());
        return ResponseEntity.noContent().build();
    }
}
