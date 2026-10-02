package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Result;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.RowOutcome;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Template;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.report.repository.ReportImportRepository;
import com.smartup24.cms.instance.report.repository.ReportImportRepository.ErrorRow;
import com.smartup24.cms.instance.report.repository.ReportImportRepository.ImportRow;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The work of one import job (ADR-0032, 10.1), run as the person who started it: the stored file read as a stream from
 * disk, its key row checked against the template the person may fill now, then its rows in batches of {@link #BATCH}
 * through the runtime ({@link EntityImporter}) — a batch in one transaction, the journal's checkpoint and the problems
 * of its rows committed with it — and finally the report, the file with a column of problems, in the instance storage.
 * A problem of the whole file ends the import with its code ({@link ImportFailure}).
 */
@Component
class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    /** The rows of one transaction (ADR-0032, 10.1). */
    static final int BATCH = 500;

    /** The most data rows of a file (ADR-0032, 10.1: an assumption until the product owner names another). */
    static final int MAX_ROWS = 100_000;

    /** The most problems kept for one row: enough to correct it, few enough for the journal. */
    static final int MAX_ROW_ERRORS = 10;

    static final String BUCKET = "instance-files";

    private final EntityImporter importer;
    private final ReportImportRepository repo;
    private final MfFileService files;
    private final StorageProvider storage;
    private final MdI18nService i18n;

    ImportRunner(
            EntityImporter importer,
            ReportImportRepository repo,
            MfFileService files,
            StorageProvider storage,
            MdI18nService i18n) {
        this.importer = importer;
        this.repo = repo;
        this.files = files;
        this.storage = storage;
        this.i18n = i18n;
    }

    /** Runs the import as the signed-in person, who is its owner. */
    void run(ImportRow row) {
        String code = importer.importable(row.entityCode());
        Template template = importer.template(code);
        Map<String, String> dictionary = i18n.effectiveDictionary(row.lang());
        Function<String, String> text = key -> dictionary.getOrDefault(key, key);
        Path spooled = spool(row);
        try (ImportFile file = ImportFile.open(spooled)) {
            List<String> keys = file.keys();
            List<ErrorRow> structure = ImportStructure.problems(template, keys, text);
            if (!structure.isEmpty()) {
                repo.addErrors(row.id(), structure);
                throw new ImportFailure(ImportFailure.STRUCTURE);
            }
            int total = file.count(keys, MAX_ROWS);
            if (total == 0) throw new ImportFailure(ImportFailure.EMPTY);
            if (total > MAX_ROWS) throw new ImportFailure(ImportFailure.TOO_MANY_ROWS);
            repo.total(row.id(), total);
            int[] done = {row.rowsDone()};
            List<EntityImporter.Row> batch = new ArrayList<>(BATCH);
            boolean apply = "apply".equals(row.mode());
            file.rows(
                    keys,
                    row.rowsDone(),
                    each -> {
                        batch.add(each);
                        if (batch.size() == BATCH) {
                            done[0] = runBatch(row, code, apply, batch, done[0], text);
                            batch.clear();
                        }
                    },
                    MAX_ROWS);
            if (!batch.isEmpty()) runBatch(row, code, apply, batch, done[0], text);
            finish(row, spooled, text);
        } catch (ImportFile.Unreadable unreadable) {
            log.warn("import_unreadable id={}", row.publicId(), unreadable);
            throw new ImportFailure(ImportFailure.UNREADABLE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(spooled);
        }
    }

    /** One batch through the runtime; the checkpoint and the problems commit with its rows. Returns the rows done. */
    private int runBatch(
            ImportRow row,
            String code,
            boolean apply,
            List<EntityImporter.Row> batch,
            int doneBefore,
            Function<String, String> text) {
        int done = doneBefore + batch.size();
        importer.run(code, apply, List.copyOf(batch), text, outcomes -> checkpoint(row, done, outcomes, text));
        return done;
    }

    private void checkpoint(ImportRow row, int done, List<RowOutcome> outcomes, Function<String, String> text) {
        int created = 0;
        int updated = 0;
        int failed = 0;
        List<ErrorRow> errors = new ArrayList<>();
        for (RowOutcome outcome : outcomes) {
            if (outcome.result() == Result.CREATED) created++;
            if (outcome.result() == Result.UPDATED) updated++;
            if (outcome.result() != Result.FAILED) continue;
            failed++;
            outcome.errors().stream()
                    .limit(MAX_ROW_ERRORS)
                    .forEach(error -> errors.add(errorRow(outcome.number(), error, text)));
        }
        repo.progress(row.id(), done, created, updated, failed);
        repo.addErrors(row.id(), errors);
    }

    /** The import is done: the report when some row was refused, then the journal row. */
    private void finish(ImportRow row, Path source, Function<String, String> text) throws IOException {
        ImportRow now = repo.find(row.publicId()).orElse(row);
        if (now.failedCount() == 0) {
            repo.markDone(row.id(), null, null);
            return;
        }
        Path report = Files.createTempFile("import-report-", ".xlsx");
        try {
            try (OutputStream out = Files.newOutputStream(report)) {
                ImportReportWriter.write(
                        source, out, text.apply("report.import.errors"), (from, to) -> repo.errors(row.id(), from, to));
            }
            String key = "imports/" + row.publicId() + ".xlsx";
            long size = Files.size(report);
            try (InputStream in = Files.newInputStream(report)) {
                storage.upload(BUCKET, key, in, size, ImportSheets.XLSX);
            }
            repo.markDone(row.id(), key, size);
        } finally {
            delete(report);
        }
    }

    /** A problem of a row as the journal keeps it: its address and code, its text in the import's language. */
    static ErrorRow errorRow(int number, FieldErrorItem error, Function<String, String> text) {
        String message = error.messageKey() == null
                ? error.message()
                : fill(text.apply(error.messageKey()), error.params() == null ? Map.of() : error.params());
        return new ErrorRow(number, cut(error.field(), 200), cut(error.code(), 64), cut(message, 1000));
    }

    /** A text with its {@code {placeholders}} filled. */
    static String fill(String template, Map<String, ?> params) {
        String filled = template;
        for (Map.Entry<String, ?> param : params.entrySet()) {
            filled = filled.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
        }
        return filled;
    }

    private static String cut(String value, int most) {
        return value.length() > most ? value.substring(0, most) : value;
    }

    /** Copies the stored file to a temporary file: the reader needs random access to the zip. */
    private Path spool(ImportRow row) {
        try {
            Path path = Files.createTempFile("import-", ".xlsx");
            try (FileDownloadStream stored = files.downloadFile(row.fileId(), row.userId())) {
                Files.copy(stored.inputStream(), path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException failure) {
                delete(path);
                throw failure;
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException leftover) {
            // A leftover temporary file costs disk, not correctness: the operating system clears it.
            log.warn("import_temp_left path={}", path, leftover);
        }
    }

    /** The structure problems of a file are the second row's: its keys name the columns. */
    static final class ImportStructure {

        private ImportStructure() {}

        static List<ErrorRow> problems(Template template, List<String> keys, Function<String, String> text) {
            Set<String> allowed = new HashSet<>();
            template.columns().forEach(column -> allowed.add(column.key()));
            Set<String> seen = new HashSet<>();
            List<ErrorRow> problems = new ArrayList<>();
            for (int c = 0; c < keys.size(); c++) {
                String key = keys.get(c);
                if (key == null || key.isEmpty()) continue;
                String at = "columns[" + c + "]";
                if (!allowed.contains(key)) {
                    problems.add(errorRow(
                            ImportSheets.KEY_ROW,
                            FieldErrorItem.keyed(
                                    at, "unknown_column", "error.report.import_column_unknown", Map.of("name", key)),
                            text));
                } else if (!seen.add(key)) {
                    problems.add(errorRow(
                            ImportSheets.KEY_ROW,
                            FieldErrorItem.keyed(
                                    at,
                                    "duplicate_column",
                                    "error.report.import_column_duplicate",
                                    Map.of("name", key)),
                            text));
                }
            }
            if (seen.isEmpty() && problems.isEmpty()) {
                problems.add(errorRow(
                        ImportSheets.KEY_ROW,
                        FieldErrorItem.keyed("columns", "required", "error.report.import_columns_missing"),
                        text));
            }
            return problems;
        }
    }
}
