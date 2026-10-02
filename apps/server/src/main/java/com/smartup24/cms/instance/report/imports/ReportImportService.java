package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.jobs.api.JobAttempt;
import com.smartup24.cms.instance.jobs.api.JobFailures;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.mf.api.FileView;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.report.api.ImportRequest;
import com.smartup24.cms.instance.report.api.ImportRowError;
import com.smartup24.cms.instance.report.api.ImportView;
import com.smartup24.cms.instance.report.export.ExportPrincipals;
import com.smartup24.cms.instance.report.repository.ReportImportRepository;
import com.smartup24.cms.instance.report.repository.ReportImportRepository.ImportRow;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports of entity records and their journal (ADR-0032, 10.1; plan 10/10, item 5.8). A request is checked at once —
 * the entity and the right's {@code import}, the mode, the person's own uploaded xlsx file — and queued; the job then
 * runs the file as the person who asked, with their rights at that moment ({@link ExportPrincipals}), and the person
 * follows it by its journal row. The template is written in the request. Only the owner sees an import; a week after
 * its start the cleanup removes its report and its row.
 */
@Service
public class ReportImportService {

    private static final Logger log = LoggerFactory.getLogger(ReportImportService.class);

    public static final String JOB = "report.import";

    /** How many imports a person may have waiting or running at once. */
    static final int MAX_ACTIVE = 3;

    /** How many problems the journal row of an import answers with; the report has them all. */
    static final int ERRORS_SHOWN = 100;

    private static final Set<String> MODES = Set.of("dry_run", "apply");

    private final EntityImporter importer;
    private final ReportImportRepository repo;
    private final ImportRunner runner;
    private final ExportPrincipals principals;
    private final MfFileService files;
    private final StorageProvider storage;
    private final MdI18nService i18n;
    private final JobQueue jobs;
    private final AuditLogService audit;

    public ReportImportService(
            EntityImporter importer,
            ReportImportRepository repo,
            ImportRunner runner,
            ExportPrincipals principals,
            MfFileService files,
            StorageProvider storage,
            MdI18nService i18n,
            // Lazy: the runner collects every job, including ours, which needs this service.
            @Lazy JobQueue jobs,
            AuditLogService audit) {
        this.importer = importer;
        this.repo = repo;
        this.runner = runner;
        this.principals = principals;
        this.files = files;
        this.storage = storage;
        this.i18n = i18n;
        this.jobs = jobs;
        this.audit = audit;
    }

    /** The template of the entity for the caller, in {@code lang}: its file name and its content. */
    public ImportFileContent template(String code, String lang) {
        EntityImporter.Template template = importer.template(code);
        Map<String, String> dictionary = i18n.effectiveDictionary(lang);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImportTemplateWriter.write(out, template, key -> dictionary.getOrDefault(key, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] content = out.toByteArray();
        return new ImportFileContent(
                template.entity().replace('.', '_') + "_import.xlsx",
                content.length,
                new ByteArrayInputStream(content));
    }

    /** A file the client downloads: its name, its size (-1 when unknown) and its content. */
    public record ImportFileContent(String fileName, long sizeBytes, InputStream content) {}

    /** Queues an import of the caller's file into the entity {@code code}. */
    @Transactional
    public ImportView request(String code, ImportRequest request) {
        long userId = SecurityContext.getCurrentUserId();
        String entity = importer.importable(code);
        String mode = request.mode() == null ? "" : request.mode().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw ApiException.validation(
                    "error.report.import_invalid",
                    List.of(FieldErrorItem.keyed("mode", "invalid", "error.report.import_mode_invalid")));
        }
        ownFile(request.fileId(), userId);
        if (repo.countActive(userId) >= MAX_ACTIVE) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.report.import_busy", Map.of("max", MAX_ACTIVE));
        }
        ImportRow row = repo.insert(userId, entity, request.fileId(), mode, request.lang());
        jobs.enqueueOnce(JOB, Map.of("importId", row.publicId().toString()));
        audit.logChange(
                "report_imports",
                row.publicId().toString(),
                "I",
                List.of("entity_code", "mode"),
                null,
                Map.of("entityCode", entity, "mode", mode));
        return view(row);
    }

    /** The caller's import with its first problems. */
    @Transactional(readOnly = true)
    public ImportView get(String publicId) {
        return view(own(publicId));
    }

    /** The report of the caller's finished import: the file with a column of problems. */
    @Transactional(readOnly = true)
    public ImportFileContent report(String publicId) {
        ImportRow row = own(publicId);
        if (!"done".equals(row.state())
                || row.reportKey() == null
                || row.expiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.report.import_report_not_ready");
        }
        String name = row.entityCode().replace('.', '_') + "_import_report.xlsx";
        long size = row.reportSize() == null ? -1 : row.reportSize();
        return new ImportFileContent(
                name,
                size,
                storage.download(ImportRunner.BUCKET, row.reportKey()).inputStream());
    }

    /**
     * The job: runs one queued import as its owner. A transient failure while attempts remain is left to the queue's
     * retry, which goes on after the checkpoint; any other ends the import failed with its code.
     */
    public void run(UUID publicId, JobAttempt attempt) {
        Optional<ImportRow> found = repo.find(publicId);
        if (found.isEmpty() || !repo.markRunning(found.get().id())) return;
        ImportRow row = found.get();
        try {
            principals.runAs(row.userId(), () -> runner.run(row));
        } catch (ImportFailure failure) {
            repo.markFailed(row.id(), failure.code());
        } catch (ApiException refused) {
            // Rights may have changed since the request: say so rather than a bare failure.
            String code = switch (refused.getErrorCode()) {
                case PERMISSION_DENIED, FORBIDDEN, NOT_FOUND -> ImportFailure.FORBIDDEN;
                default -> ImportFailure.FAILED;
            };
            log.warn("import_refused id={} code={}", publicId, refused.getErrorCode());
            repo.markFailed(row.id(), code);
        } catch (RuntimeException failure) {
            if (JobFailures.isTransient(failure) && !attempt.last()) throw failure;
            log.warn("import_failed id={}", publicId, failure);
            repo.markFailed(row.id(), ImportFailure.FAILED);
        }
    }

    /** Removes the imports whose week is over: the report first, then the journal row with its problems. */
    public int cleanup() {
        int removed = 0;
        for (ImportRow row : repo.findExpired(Instant.now(), 500)) {
            if (row.reportKey() != null) {
                try {
                    storage.delete(ImportRunner.BUCKET, row.reportKey());
                } catch (RuntimeException e) {
                    log.warn("Could not delete import report {}; the row stays for the next run", row.reportKey(), e);
                    continue;
                }
            }
            repo.delete(row.id());
            removed++;
        }
        return removed;
    }

    /** The file is the caller's own upload of an xlsx workbook; anything else reads as no file (422). */
    private void ownFile(UUID fileId, long userId) {
        FieldErrorItem missing = FieldErrorItem.keyed("fileId", "not_found", "error.report.import_file_not_found");
        if (fileId == null) throw ApiException.validation("error.report.import_invalid", List.of(missing));
        FileView file;
        try {
            file = files.getFileMetadata(fileId, userId);
        } catch (ApiException notVisible) {
            throw ApiException.validation("error.report.import_invalid", List.of(missing));
        }
        if (file.createdBy() == null || file.createdBy() != userId) {
            throw ApiException.validation("error.report.import_invalid", List.of(missing));
        }
        if (!xlsx(file)) {
            throw ApiException.validation(
                    "error.report.import_invalid",
                    List.of(FieldErrorItem.keyed("fileId", "file_type", "error.report.import_file_type")));
        }
    }

    /** An xlsx workbook: its type, or a zip named {@code .xlsx} (a browser that does not know the type sends so). */
    private static boolean xlsx(FileView file) {
        String name = file.originalName() == null ? "" : file.originalName().toLowerCase(Locale.ROOT);
        String type = file.mimeType() == null ? "" : file.mimeType();
        return ImportSheets.XLSX.equals(type) || (name.endsWith(".xlsx") && type.contains("zip"));
    }

    private ImportRow own(String publicId) {
        UUID id;
        try {
            id = UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.report.import_not_found");
        }
        long userId = SecurityContext.getCurrentUserId();
        return repo.find(id)
                .filter(row -> row.userId() == userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.report.import_not_found"));
    }

    private ImportView view(ImportRow row) {
        List<ImportRowError> errors = repo.errors(row.id(), ERRORS_SHOWN).stream()
                .map(error -> new ImportRowError(error.rowNo(), error.field(), error.code(), error.message()))
                .toList();
        return new ImportView(
                row.publicId(),
                row.entityCode(),
                row.mode(),
                row.state(),
                row.rowsTotal(),
                row.rowsDone(),
                row.createdCount(),
                row.updatedCount(),
                row.failedCount(),
                row.errorCode(),
                errors,
                "done".equals(row.state()) && row.reportKey() != null,
                row.createdAt(),
                row.finishedAt(),
                row.expiresAt());
    }
}
