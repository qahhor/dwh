package com.greenwhite.dwh.instance.report.export;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.common.query.QueryListRegistry;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.fnd.jobs.FndJobRunner;
import com.greenwhite.dwh.instance.md.service.MdI18nService;
import com.greenwhite.dwh.instance.report.repository.ReportExportRepository;
import com.greenwhite.dwh.instance.report.repository.ReportExportRepository.ExportRow;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Asynchronous exports of registry lists to xlsx and their journal (ADR-0018).
 * A request is checked at once — the list, the right to it, the filter, sort
 * and search, the columns — and queued; the job then pages through the list
 * as the person who asked, with their rights at that moment, writes the file
 * to the instance storage and marks the export done. The file stays for a
 * week; a person sees and downloads only their own exports.
 */
@Service
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);

    public static final String JOB = "report.export";
    static final String BUCKET = "instance-files";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** How many exports a person may have waiting or running at once. */
    static final int MAX_ACTIVE = 3;
    static final int JOURNAL_SIZE = 50;
    private static final int PAGE_SIZE = 200;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    public static final String EXPORT_LIST_UNKNOWN = "EXPORT_LIST_UNKNOWN";
    public static final String EXPORT_INVALID = "EXPORT_INVALID";
    public static final String EXPORT_BUSY = "EXPORT_BUSY";
    public static final String EXPORT_NOT_FOUND = "EXPORT_NOT_FOUND";
    public static final String EXPORT_NOT_READY = "EXPORT_NOT_READY";
    static final String EXPORT_FORBIDDEN = "EXPORT_FORBIDDEN";
    static final String EXPORT_FAILED = "EXPORT_FAILED";

    private final ReportExportRepository repo;
    private final QueryListRegistry registry;
    private final Map<String, QueryListExporter> exporters;
    private final ExportPrincipals principals;
    private final MdI18nService i18n;
    private final StorageProvider storage;
    private final FndJobRunner jobs;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final int maxRows;

    public ReportExportService(ReportExportRepository repo, QueryListRegistry registry, List<QueryListExporter> exporters,
                               ExportPrincipals principals, MdI18nService i18n, StorageProvider storage,
                               // Lazy: the runner collects every job, including ours, which needs this service.
                               @Lazy FndJobRunner jobs, AuditLogService audit, ObjectMapper json,
                               @Value("${dwh.reports.export.max-rows:50000}") int maxRows) {
        this.repo = repo;
        this.registry = registry;
        this.exporters = exporters.stream().collect(Collectors.toUnmodifiableMap(QueryListExporter::code, Function.identity()));
        this.principals = principals;
        this.i18n = i18n;
        this.storage = storage;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.maxRows = maxRows;
    }

    /** What the client asks to export: the list as it stands on screen. */
    public record ExportRequest(String list, String filter, String sort, String q, List<String> columns,
                                Map<String, String> options, String lang) {
    }

    @Transactional
    public ExportRow request(ExportRequest request) {
        long userId = SecurityContext.getCurrentUserId();
        QueryList list = registry.find(request.list())
                .filter(found -> SecurityContext.hasPermission(found.form(), found.action()))
                .filter(found -> exporters.containsKey(found.code()))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, EXPORT_LIST_UNKNOWN));
        QueryListExporter exporter = exporters.get(list.code());
        // The same checks the list endpoint makes: a bad filter is refused now, not in the job.
        QueryCompiler.compile(list, request.filter(), request.sort(), 1, null, request.q());
        Map<String, String> options = request.options() == null ? Map.of() : request.options();
        List<FieldErrorItem> errors = new ArrayList<>();
        options.keySet().stream().filter(key -> !exporter.options().contains(key))
                .forEach(key -> errors.add(new FieldErrorItem("options." + key, EXPORT_INVALID, "unknown option " + key)));
        if (errors.isEmpty()) {
            exporter.checkOptions(options).forEach(error -> errors.add(
                    new FieldErrorItem("options." + error.field(), EXPORT_INVALID, error.message())));
        }
        if (request.columns() != null) {
            for (int i = 0; i < request.columns().size(); i++) {
                String key = request.columns().get(i);
                if (list.viewerField(key).isEmpty()) {
                    errors.add(new FieldErrorItem("columns[" + i + "]", EXPORT_INVALID, "unknown column " + key));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(EXPORT_INVALID, errors);
        }
        if (repo.countActive(userId) >= MAX_ACTIVE) {
            throw ApiException.conflict(ErrorCode.CONFLICT, EXPORT_BUSY);
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        putIfPresent(stored, "filter", request.filter());
        putIfPresent(stored, "sort", request.sort());
        putIfPresent(stored, "q", request.q());
        if (request.columns() != null && !request.columns().isEmpty()) stored.put("columns", request.columns());
        if (!options.isEmpty()) stored.put("options", options);
        putIfPresent(stored, "lang", request.lang());
        ExportRow row = repo.insert(userId, list.code(), toJson(stored));
        jobs.enqueueOnce(JOB, Map.of("exportId", row.publicId().toString()));
        audit.logChange("report_exports", row.publicId().toString(), "I", List.of("list_code"), null,
                Map.of("listCode", list.code()));
        return row;
    }

    @Transactional(readOnly = true)
    public List<ExportRow> journal() {
        return repo.listForUser(SecurityContext.getCurrentUserId(), JOURNAL_SIZE);
    }

    /** A person's own finished export; somebody else's is as unknown as a missing one. */
    @Transactional(readOnly = true)
    public ExportFile file(String publicId) {
        ExportRow row = own(publicId);
        if (!"done".equals(row.state()) || row.expiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict(ErrorCode.CONFLICT, EXPORT_NOT_READY);
        }
        return new ExportFile(row.fileName(), row.sizeBytes() == null ? -1 : row.sizeBytes(),
                storage.download(BUCKET, row.storageKey()).inputStream());
    }

    public record ExportFile(String fileName, long sizeBytes, InputStream content) {
    }

    /** The job: runs one queued export as its owner. */
    public void run(UUID publicId) {
        Optional<ExportRow> found = repo.find(publicId);
        if (found.isEmpty() || !repo.markRunning(found.get().id())) {
            return;
        }
        ExportRow row = found.get();
        try {
            principals.runAs(row.userId(), () -> write(row));
        } catch (ApiException e) {
            // Rights may have changed since the request: say so rather than a bare failure.
            repo.markFailed(row.id(), switch (e.getErrorCode()) {
                case PERMISSION_DENIED, FORBIDDEN -> EXPORT_FORBIDDEN;
                case VALIDATION_FAILED -> EXPORT_INVALID;
                default -> EXPORT_FAILED;
            });
        } catch (RuntimeException e) {
            log.warn("Export {} failed", publicId, e);
            repo.markFailed(row.id(), EXPORT_FAILED);
        }
    }

    /** Removes exports whose week is over: the file first, then the journal row. */
    public int cleanup() {
        int removed = 0;
        for (ExportRow row : repo.findExpired(Instant.now(), JOURNAL_SIZE * 10)) {
            if (row.storageKey() != null) {
                try {
                    storage.delete(BUCKET, row.storageKey());
                } catch (RuntimeException e) {
                    log.warn("Could not delete export file {}; the row stays for the next run", row.storageKey(), e);
                    continue;
                }
            }
            repo.delete(row.id());
            removed++;
        }
        return removed;
    }

    private void write(ExportRow row) {
        StoredRequest request = readRequest(row.request());
        QueryList list = registry.find(row.listCode())
                .filter(found -> SecurityContext.hasPermission(found.form(), found.action()))
                .orElseThrow(() -> ApiException.permissionDenied(row.listCode(), "view"));
        QueryListExporter exporter = Optional.ofNullable(exporters.get(list.code()))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, EXPORT_LIST_UNKNOWN));
        List<QueryField> fields = columns(list, request.columns());
        Map<String, String> dictionary = i18n.effectiveDictionary(request.lang());
        Function<String, String> text = key -> dictionary.getOrDefault(key, key);

        Path temp = null;
        try {
            temp = Files.createTempFile("export-", ".xlsx");
            int written;
            boolean truncated = false;
            try (OutputStream out = Files.newOutputStream(temp);
                 ExportWorkbookWriter writer = new ExportWorkbookWriter(out, row.listCode(), fields, text,
                         ExportWorkbookWriter.APPLICATION, ExportWorkbookWriter.APP_VERSION)) {
                String cursor = null;
                do {
                    KeysetPage<?> page = exporter.page(PAGE_SIZE, cursor, request.filter(), request.sort(), request.q(),
                            request.options());
                    for (Object item : page.items()) {
                        if (writer.rows() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        writer.add(json.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                    }
                    cursor = page.hasMore() ? page.nextCursor() : null;
                    if (cursor != null && writer.rows() >= maxRows) {
                        truncated = true;
                    }
                } while (cursor != null && !truncated);
                written = writer.rows();
            }
            String key = "exports/" + row.publicId() + ".xlsx";
            long size = Files.size(temp);
            try (InputStream in = Files.newInputStream(temp)) {
                storage.upload(BUCKET, key, in, size, XLSX);
            }
            repo.markDone(row.id(), written, truncated, fileName(row), key, size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless; the operating system clears it.
                }
            }
        }
    }

    /** The requested columns the person may see, in their order; none asked — the list's own columns. */
    private static List<QueryField> columns(QueryList list, List<String> requested) {
        List<QueryField> visible = list.viewerFields();
        if (requested == null || requested.isEmpty()) {
            return visible.stream().filter(QueryField::defaultVisible).toList();
        }
        return requested.stream().map(list::viewerField).flatMap(Optional::stream).toList();
    }

    private ExportRow own(String publicId) {
        UUID id;
        try {
            id = UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, EXPORT_NOT_FOUND);
        }
        long userId = SecurityContext.getCurrentUserId();
        return repo.find(id).filter(row -> row.userId() == userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, EXPORT_NOT_FOUND));
    }

    private static String fileName(ExportRow row) {
        return row.listCode().replace('.', '_') + "_" + STAMP.format(row.createdAt()) + ".xlsx";
    }

    private record StoredRequest(String filter, String sort, String q, List<String> columns,
                                 Map<String, String> options, String lang) {
    }

    private StoredRequest readRequest(String raw) {
        try {
            Map<String, Object> map = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) map.get("columns");
            @SuppressWarnings("unchecked")
            Map<String, String> options = (Map<String, String>) map.getOrDefault("options", Map.of());
            return new StoredRequest((String) map.get("filter"), (String) map.get("sort"), (String) map.get("q"),
                    columns, options, (String) map.get("lang"));
        } catch (JacksonException e) {
            throw new IllegalStateException("Broken export request", e);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
