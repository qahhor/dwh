package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.fnd.api.FndJobQueue;
import com.smartup24.cms.instance.fnd.api.FndVersions;
import com.smartup24.cms.instance.mf.api.StoredFile;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.upl.UplLimits;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageItem;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Приём файла: проверка полей, поиск анкеты на начало периода, укладка файла в хранилище каркаса
 * и запись пакета вместе с заданием на разбор (контракт И5, раздел 3 «Порядок приёма»).
 * Разбора в запросе нет: он идёт заданием очереди.
 */
@Service
public class UplUploadService {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UplSourceService sources;
    private final FndVersions versioning;
    private final MfFileService files;
    private final UplPackageService packages;
    private final FndJobQueue jobs;
    private final TransactionTemplate tx;

    public UplUploadService(
            UplSourceService sources,
            FndVersions versioning,
            MfFileService files,
            UplPackageService packages,
            FndJobQueue jobs,
            TransactionTemplate tx) {
        this.sources = sources;
        this.versioning = versioning;
        this.files = files;
        this.packages = packages;
        this.jobs = jobs;
        this.tx = tx;
    }

    /** Части запроса приёма как они пришли с формы; {@code content} — тело файла. */
    /** Receives the file as {@link #receive} does and answers the package as the API shows it (plan 10/10, 3.2). */
    public PackageItem receiveItem(Upload upload, long userId) {
        return PackageItem.of(receive(upload, userId));
    }

    public record Upload(
            String sourceId,
            String periodFrom,
            String periodTo,
            boolean filePresent,
            String fileName,
            String mimeType,
            long sizeBytes,
            InputStreamSource content) {}

    /**
     * Принимает файл и ставит его в очередь на разбор. Транзакции на весь приём нет намеренно:
     * файл кладётся в хранилище каркаса снаружи, а пакет и задание появляются одной транзакцией.
     */
    public PackageRow receive(Upload upload, long userId) {
        List<FieldErrorItem> errors = UplUploadValidator.validate(
                upload.sourceId(),
                upload.periodFrom(),
                upload.periodTo(),
                upload.filePresent(),
                upload.fileName(),
                upload.sizeBytes());
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.upl.package_invalid", errors);
        }
        long sourceId = Long.parseLong(upload.sourceId().strip());
        LocalDate periodFrom = LocalDate.parse(upload.periodFrom().strip());
        LocalDate periodTo = LocalDate.parse(upload.periodTo().strip());

        sources.getSource(sourceId);
        int formatVersion = versioning
                .versionAt(UplPref.TABLE_FORMAT_VERSIONS, sourceId, periodFrom)
                .orElseThrow(() -> ApiException.conflict(ErrorCode.CONFLICT, "error.upl.pkg_no_format_at_date"));
        if (upload.sizeBytes() > UplLimits.MAX_FILE_BYTES) {
            throw ApiException.badRequest(
                    ErrorCode.FILE_SIZE_EXCEEDED,
                    "error.upl.pkg_file_too_large",
                    Map.of("megabytes", UplLimits.MAX_FILE_MEGABYTES));
        }

        StoredFile file = store(upload, userId);
        return tx.execute(status -> registerWithParseJob(sourceId, formatVersion, periodFrom, periodTo, file, userId));
    }

    /** Кладёт файл в хранилище каркаса; отказы хранилища уходят наверх как есть. */
    private StoredFile store(Upload upload, long userId) {
        String mimeType = upload.mimeType() == null || upload.mimeType().isBlank() ? XLSX_MIME : upload.mimeType();
        try (InputStream content = upload.content().getInputStream()) {
            return files.store(upload.fileName(), mimeType, content, upload.sizeBytes(), userId);
        } catch (IOException failure) {
            throw new UncheckedIOException("Тело загружаемого файла не читается", failure);
        }
    }

    /** Пакет и задание на его разбор появляются вместе или не появляются вовсе. */
    private PackageRow registerWithParseJob(
            long sourceId, int formatVersion, LocalDate periodFrom, LocalDate periodTo, StoredFile file, long userId) {
        PackageRow row = packages.register(new NewPackage(
                sourceId,
                formatVersion,
                periodFrom,
                periodTo,
                file.id(),
                file.originalName(),
                file.sha256(),
                file.sizeBytes(),
                userId));
        jobs.enqueueOnce(UplPref.JOB_PARSE, Map.of("packageId", row.publicId().toString()));
        return row;
    }
}
