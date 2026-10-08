package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.versioning.Versions;
import com.smartup24.cms.instance.jobs.api.JobQueue;
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
 * File upload: field checks, finding the format valid at the period start, storing the file in the platform storage
 * and writing the package together with the parse job.
 * There is no parsing in the request: it runs as a queue job.
 */
@Service
public class UplUploadService {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UplSourceService sources;
    private final Versions versioning;
    private final MfFileService files;
    private final UplPackageService packages;
    private final JobQueue jobs;
    private final TransactionTemplate tx;

    public UplUploadService(
            UplSourceService sources,
            Versions versioning,
            MfFileService files,
            UplPackageService packages,
            JobQueue jobs,
            TransactionTemplate tx) {
        this.sources = sources;
        this.versioning = versioning;
        this.files = files;
        this.packages = packages;
        this.jobs = jobs;
        this.tx = tx;
    }

    /** Parts of the upload request as they came from the form; {@code content} is the file body. */
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
     * Accepts a file and queues it for parsing. There is deliberately no transaction for the whole upload:
     * the file is put into the platform storage outside it, while the package and the job appear in one transaction.
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

    /** Puts the file into the platform storage; storage refusals propagate as they are. */
    private StoredFile store(Upload upload, long userId) {
        String mimeType = upload.mimeType() == null || upload.mimeType().isBlank() ? XLSX_MIME : upload.mimeType();
        try (InputStream content = upload.content().getInputStream()) {
            return files.store(upload.fileName(), mimeType, content, upload.sizeBytes(), userId);
        } catch (IOException failure) {
            throw new UncheckedIOException("The body of the uploaded file cannot be read", failure);
        }
    }

    /** The package and its parse job appear together or not at all. */
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
