package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.instance.fnd.FndActor;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.dwh.FndRawRow;
import com.smartup24.cms.instance.fnd.dwh.FndRawWriter;
import com.smartup24.cms.instance.fnd.jobs.FndJobAttempt;
import com.smartup24.cms.instance.fnd.jobs.FndJobFailures;
import com.smartup24.cms.instance.fnd.jobs.FndJobHandler;
import com.smartup24.cms.instance.fnd.load.FndLoad;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.parse.UplParseResult;
import com.smartup24.cms.instance.upl.parse.UplSpooledFile;
import com.smartup24.cms.instance.upl.parse.UplXlsxParser;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Задание «применить пакет» (план 10/10, п. 3.9), queued by {@link UplApplyService#request}. It reads the stored file
 * from a temporary copy on disk and pushes each parsed row straight into one {@code COPY} of pg-dwh: no list of rows,
 * no reading raw back — the reconciliation takes the count {@code COPY} returns. Raw lives in the second database, so a
 * failed write becomes the package's reason, not a failed job: the job closes the package «отклонён системой» and ends.
 * A transient failure (pg-dwh away, storage not readable) is first left to the runner's retries; only the last attempt
 * closes the package with it.
 *
 * <p>The runner calls it with no transaction open (item 3.8); the job opens one short transaction to close the
 * package. It may run again after its node died. Raw is written in one pg-dwh transaction, so a load has all its rows
 * or none: rows already there mean an earlier attempt committed them and died before closing the package, and the
 * retry closes it with their count instead of writing them twice. A package no longer «применяется» — closed by an
 * earlier attempt or by {@link UplApplyRecoveryJob} — is left alone.
 */
@Component
public class UplApplyJob implements FndJobHandler {

    private static final String ARG_PACKAGE_ID = "packageId";
    private static final String ARG_USER_ID = "userId";

    private static final Logger log = LoggerFactory.getLogger(UplApplyJob.class);

    private final UplPackageRepository repo;
    private final UplSourceService sources;
    private final MfFileService files;
    private final UplXlsxParser parser;
    private final FndLoadService loads;
    private final FndRawWriter raw;
    private final FndActors actors;
    private final TransactionTemplate tx;

    public UplApplyJob(
            UplPackageRepository repo,
            UplSourceService sources,
            MfFileService files,
            UplXlsxParser parser,
            FndLoadService loads,
            FndRawWriter raw,
            FndActors actors,
            TransactionTemplate tx) {
        this.repo = repo;
        this.sources = sources;
        this.files = files;
        this.parser = parser;
        this.loads = loads;
        this.raw = raw;
        this.actors = actors;
        this.tx = tx;
    }

    /** The arguments of the job for one package; the user is the one who asked, the actor of the load's audit. */
    static Map<String, Object> args(UUID packageId, long userId) {
        return Map.of(ARG_PACKAGE_ID, packageId.toString(), ARG_USER_ID, userId);
    }

    @Override
    public String code() {
        return UplPref.JOB_APPLY;
    }

    /** Outside the queue (tests, tools): the only attempt, so a failed write closes the package at once. */
    @Override
    public void run(Map<String, Object> args) {
        run(args, FndJobAttempt.only());
    }

    /**
     * A transient failure of the write (pg-dwh away, the stored file not readable for a moment) fails the attempt
     * while attempts remain, so the runner retries it (plan 10/10, item 3.8); the last attempt, or a failure a retry
     * would not fix, closes the package «отклонён системой».
     */
    @Override
    public void run(Map<String, Object> args, FndJobAttempt attempt) {
        UUID publicId = packageId(args);
        long userId = userId(args);
        PackageRow row = repo.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Пакет " + publicId + " не найден"));
        if (!UplPackageModel.APPLYING.equals(row.status()) || !loadOpen(row)) {
            return;
        }
        Long rawRows = writeRaw(row, attempt);
        FndActor actor = actors.user(userId);
        tx.executeWithoutResult(status -> finish(row.id(), rawRows, actor));
    }

    private boolean loadOpen(PackageRow row) {
        return loads.find(row.loadId())
                .filter(load -> FndLoad.PENDING.equals(load.status()))
                .isPresent();
    }

    /**
     * Writes the rows of the file into raw; {@code null} — the write failed for good and the reason goes to the
     * package. A transient failure while attempts remain is rethrown for the runner's retry.
     */
    private Long writeRaw(PackageRow row, FndJobAttempt attempt) {
        try {
            long already = raw.count(row.loadId());
            if (already > 0) {
                log.warn("Пакет {}: строки уже в raw ({}), повтор закрывает пакет без записи", row.publicId(), already);
                return already;
            }
            return copyRows(row);
        } catch (IOException | RuntimeException failure) {
            if (!attempt.last() && FndJobFailures.isTransient(failure)) {
                log.warn(
                        "upl_apply_retry package={} attempt={} max_attempts={}",
                        row.publicId(),
                        attempt.number(),
                        attempt.maxAttempts(),
                        failure);
                throw unchecked(failure);
            }
            log.error("Пакет {}: строки не записаны в raw", row.publicId(), failure);
            return null;
        }
    }

    private static RuntimeException unchecked(Exception failure) {
        return failure instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) failure;
    }

    /** Each parsed row goes into the copy as it is read; the parser's verdict must match the upload's. */
    private long copyRows(PackageRow row) throws IOException {
        FormatVersion format = sources.getVersion(row.sourceId(), row.formatVersion());
        try (FileDownloadStream file = files.downloadFile(row.fileId());
                UplSpooledFile spooled = UplSpooledFile.of(file.inputStream())) {
            return raw.copy(row.loadId(), row.fileId(), sink -> {
                long[] rowNo = {0};
                UplParseResult result = parser.parse(
                        spooled.path(),
                        format,
                        data -> sink.accept(
                                new FndRawRow(++rowNo[0], data.sheet(), data.sourceRowNo(), data.fields())));
                if (result.outcome() != UplParseResult.Outcome.VERIFIED) {
                    throw new IllegalStateException(
                            "Повторный разбор файла пакета " + row.publicId() + " не дал «проверен»");
                }
            });
        }
    }

    private void finish(long packageId, Long rawRows, FndActor actor) {
        PackageRow row = repo.lockById(packageId)
                .orElseThrow(() -> new IllegalStateException("Пакет " + packageId + " пропал во время применения"));
        if (!UplPackageModel.APPLYING.equals(row.status())) {
            // The recovery job closed it, with its load, while the rows were being written
            return;
        }
        actors.apply(actor);
        boolean reconciled = rawRows != null
                && rawRows == row.rowsTotal().longValue()
                && row.rowsTotal() == row.rowsAccepted() + row.rowsRejected();
        if (reconciled) {
            loads.apply(row.loadId(), row.rowsTotal(), row.rowsAccepted(), row.rowsRejected(), actor);
            requireOne(repo.markApplied(row.id(), rawRows.intValue()), row);
            loads.log(
                    row.publicId(),
                    "applied",
                    UplPackageModel.VERIFIED,
                    UplPackageModel.APPLIED,
                    actor,
                    null,
                    row.fileSha256());
        } else if (rawRows == null) {
            loads.fail(row.loadId(), UplApplyService.UPL_PKG_RAW_WRITE_FAILED, actor);
            requireOne(repo.markApplyRejected(row.id(), UplApplyService.UPL_PKG_RAW_WRITE_FAILED, Map.of(), null), row);
        } else {
            loads.fail(
                    row.loadId(),
                    UplApplyService.UPL_PKG_RECONCILIATION + ": в файле " + row.rowsTotal() + ", в raw " + rawRows,
                    actor);
            requireOne(
                    repo.markApplyRejected(
                            row.id(),
                            UplApplyService.UPL_PKG_RECONCILIATION,
                            Map.of("fileRows", row.rowsTotal(), "rawRows", rawRows.intValue()),
                            rawRows.intValue()),
                    row);
        }
    }

    private static void requireOne(int updated, PackageRow row) {
        if (updated != 1) {
            throw new IllegalStateException("Пакет " + row.publicId() + " уже не в статусе «применяется»");
        }
    }

    private static UUID packageId(Map<String, Object> args) {
        Object value = args == null ? null : args.get(ARG_PACKAGE_ID);
        if (value == null) {
            throw new IllegalStateException("В задании " + UplPref.JOB_APPLY + " нет аргумента " + ARG_PACKAGE_ID);
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notUuid) {
            throw new IllegalStateException(
                    "Аргумент " + ARG_PACKAGE_ID + " задания " + UplPref.JOB_APPLY
                            + " не является идентификатором пакета",
                    notUuid);
        }
    }

    private static long userId(Map<String, Object> args) {
        if (args.get(ARG_USER_ID) instanceof Number user) {
            return user.longValue();
        }
        throw new IllegalStateException("В задании " + UplPref.JOB_APPLY + " нет аргумента " + ARG_USER_ID);
    }
}
