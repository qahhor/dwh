package com.smartup24.cms.instance.upl.parse;

import com.smartup24.cms.instance.fnd.jobs.FndJobAttempt;
import com.smartup24.cms.instance.fnd.jobs.FndJobFailures;
import com.smartup24.cms.instance.fnd.jobs.FndJobHandler;
import com.smartup24.cms.instance.fnd.jobs.FndJobNotRetryableException;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.upl.upload.UplPackageService;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Задание «разобрать файл пакета»: берёт файл из хранилища каркаса, разбирает его по анкете,
 * действовавшей на начало периода, и записывает итог. A transient failure is left to the runner's retries; any
 * other failure, or one on the last attempt, closes the package with {@link #UPL_PKG_INTERNAL} and fails the job with
 * no retry. Parsing runs outside a transaction (the queue holds none while a job works, plan 10/10, item 3.8).
 */
@Component
public class UplParseJob implements FndJobHandler {

    /** Пакет отклонён: разбор упал по внутренней ошибке. */
    public static final String UPL_PKG_INTERNAL = "UPL_PKG_INTERNAL";

    private static final String ARG_PACKAGE_ID = "packageId";

    private final UplPackageService packages;
    private final UplSourceService sources;
    private final MfFileService files;
    private final UplXlsxParser parser;

    public UplParseJob(
            UplPackageService packages, UplSourceService sources, MfFileService files, UplXlsxParser parser) {
        this.packages = packages;
        this.sources = sources;
        this.files = files;
        this.parser = parser;
    }

    @Override
    public String code() {
        return UplPref.JOB_PARSE;
    }

    /** Outside the queue (tests, tools): the only attempt, so a failure closes the package at once. */
    @Override
    public void run(Map<String, Object> args) {
        run(args, FndJobAttempt.only());
    }

    @Override
    public void run(Map<String, Object> args, FndJobAttempt attempt) {
        UUID publicId = packageId(args);
        PackageRow row = packages.find(publicId)
                .orElseThrow(() -> new IllegalStateException("Пакет " + publicId + " не найден"));
        if (!UplPackageModel.RECEIVED.equals(row.status())) {
            return;
        }
        UplParseResult result = parse(row, attempt);
        packages.saveParseResult(row.id(), result);
    }

    /**
     * Reads the format and parses the file of the package. A transient failure (storage or database away) while
     * attempts remain fails the attempt and leaves the package «получен» for the runner's retry (plan 10/10, item
     * 3.8); any other failure, or one on the last attempt, closes the package with an internal error and fails the job
     * with no retry: another attempt would find the package closed and change nothing.
     */
    private UplParseResult parse(PackageRow row, FndJobAttempt attempt) {
        try (FileDownloadStream file = files.downloadFile(row.fileId());
                UplSpooledFile spooled = UplSpooledFile.of(file.inputStream())) {
            FormatVersion format = sources.getVersion(row.sourceId(), row.formatVersion());
            return parser.parse(spooled.path(), format);
        } catch (IOException failure) {
            throw settle(
                    row,
                    attempt,
                    new UncheckedIOException("Файл пакета " + row.publicId() + " не читается из хранилища", failure));
        } catch (RuntimeException failure) {
            throw settle(row, attempt, failure);
        }
    }

    /** The exception the attempt ends with; closes the package unless the failure is left to a retry. */
    private RuntimeException settle(PackageRow row, FndJobAttempt attempt, RuntimeException failure) {
        if (!attempt.last() && FndJobFailures.isTransient(failure)) {
            return failure;
        }
        packages.rejectInNewTransaction(row.id(), UPL_PKG_INTERNAL);
        return new FndJobNotRetryableException(
                "package " + row.publicId() + " closed with " + UPL_PKG_INTERNAL, failure);
    }

    private static UUID packageId(Map<String, Object> args) {
        Object raw = args == null ? null : args.get(ARG_PACKAGE_ID);
        if (raw == null) {
            throw new IllegalStateException("В задании " + UplPref.JOB_PARSE + " нет аргумента " + ARG_PACKAGE_ID);
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException notUuid) {
            throw new IllegalStateException(
                    "Аргумент " + ARG_PACKAGE_ID + " задания " + UplPref.JOB_PARSE
                            + " не является идентификатором пакета",
                    notUuid);
        }
    }
}
