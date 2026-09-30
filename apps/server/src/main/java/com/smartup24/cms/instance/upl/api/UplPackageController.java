package com.smartup24.cms.instance.upl.api;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageErrors;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageItem;
import com.smartup24.cms.instance.upl.upload.UplApplyService;
import com.smartup24.cms.instance.upl.upload.UplErrorReportBuilder;
import com.smartup24.cms.instance.upl.upload.UplPackageService;
import com.smartup24.cms.instance.upl.upload.UplUploadService;
import com.smartup24.cms.instance.upl.upload.UplUploadService.Upload;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * API загрузок файлов: приём файла, список пакетов и ошибки пакета (контракт И5), применение пакета (И6). Both the
 * upload and the apply answer 202: the work is a job, the package is the status resource.
 */
@RestController
@RequestMapping(UplPackageController.BASE)
public class UplPackageController {

    static final String BASE = "/api/v1/upl/packages";

    private final UplUploadService uploads;
    private final UplPackageService packages;
    private final UplApplyService applies;
    private final UplErrorReportBuilder reports;
    private final MdI18nService i18n;

    public UplPackageController(
            UplUploadService uploads,
            UplPackageService packages,
            UplApplyService applies,
            UplErrorReportBuilder reports,
            MdI18nService i18n) {
        this.uploads = uploads;
        this.packages = packages;
        this.applies = applies;
        this.reports = reports;
        this.i18n = i18n;
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    /**
     * Принимает файл: 202 и пакет в статусе «получен». Части запроса не обязательны для каркаса —
     * их отсутствие проверяет сервис и отвечает одним списком ошибок, а не отказом разбора запроса.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_UPLOAD)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<PackageItem> upload(
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo,
            @RequestParam(name = "file", required = false) MultipartFile file) {
        Upload upload = new Upload(
                sourceId,
                periodFrom,
                periodTo,
                file != null,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? 0L : file.getSize(),
                file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(uploads.receiveItem(upload, userId()));
    }

    @GetMapping
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<KeysetPage<PackageItem>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(packages.items(limit, cursor, filter, sort, q));
    }

    /** One upload, as the list shows it; the overview links straight to its card. */
    @GetMapping("/{id}")
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<PackageItem> get(@PathVariable String id) {
        return ResponseEntity.ok(packages.item(id));
    }

    @GetMapping("/{id}/errors")
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<PackageErrors> errors(@PathVariable String id) {
        return ResponseEntity.ok(packages.errorItems(id));
    }

    /**
     * Ошибки пакета файлом xlsx (роадмап п. 21): что загружено и чем кончилось, затем каждая сохранённая
     * ошибка с адресом и словами, а не кодом, — на языке, который попросили (по умолчанию русский).
     */
    @GetMapping("/{id}/errors/file")
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<byte[]> errorsFile(@PathVariable String id, @RequestParam(required = false) String lang) {
        Map<String, String> dictionary = i18n.effectiveDictionary(lang);
        UplErrorReportBuilder.ReportFile file =
                packages.errorReport(id, reports, (key, params) -> fill(dictionary.getOrDefault(key, key), params));
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
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

    /**
     * Ставит применение пакета «проверен» в очередь (план 10/10, п. 3.9): 202, пакет «применяется» и {@code Location}
     * — the package itself, which the client polls until it turns «применён» or «отклонён системой».
     */
    @PostMapping("/{id}/apply")
    @RequiresPermission(form = UplPref.FORM_PACKAGES, action = UplPref.ACTION_APPLY)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<PackageItem> apply(@PathVariable String id) {
        PackageItem item = applies.requestItem(id, userId());
        return ResponseEntity.accepted()
                .location(URI.create(BASE + "/" + item.id()))
                .body(item);
    }
}
