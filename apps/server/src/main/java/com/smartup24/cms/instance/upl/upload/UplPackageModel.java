package com.smartup24.cms.instance.upl.upload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Upload package and its errors as stored in {@code upl_packages} and {@code upl_package_errors} (V114). */
public final class UplPackageModel {

    /** The file is accepted and waits to be parsed by a job. */
    public static final String RECEIVED = "received";
    /** The file is rejected by the system: parsing produced no rows, the reason is in {@code rejectCode}. */
    public static final String REJECTED = "rejected";
    /** The file is parsed: row counters are filled in, cell errors are stored. */
    public static final String VERIFIED = "verified";
    /**
     * Apply requested (plan 10/10, item 3.9): the load of the foundation is open and the job that streams the rows into
     * raw is queued or running; the job turns the package applied or rejected, the recovery job does if the job died.
     * Not a value of the column: it is a verified package with a load number, read as {@link
     * UplPackageRepository#STATUS_SQL}, so the table and its check stay as V114 made them.
     */
    public static final String APPLYING = "applying";
    /** A verified package was applied by a foundation load. */
    public static final String APPLIED = "applied";

    private UplPackageModel() {}

    /** A package row together with the source code and name. */
    public record PackageRow(
            long id,
            UUID publicId,
            long sourceId,
            String sourceCode,
            String sourceName,
            int formatVersion,
            LocalDate periodFrom,
            LocalDate periodTo,
            UUID fileId,
            String fileName,
            String fileSha256,
            long fileSizeBytes,
            String status,
            Integer rowsTotal,
            Integer rowsAccepted,
            Integer rowsRejected,
            Integer errorsTotal,
            String rejectCode,
            Map<String, Object> rejectParams,
            Long loadId,
            Integer rawRows,
            Instant uploadedAt,
            String uploadedBy) {}

    /** Data of a new package: everything known at the moment the file is accepted. */
    public record NewPackage(
            long sourceId,
            int formatVersion,
            LocalDate periodFrom,
            LocalDate periodTo,
            UUID fileId,
            String fileName,
            String fileSha256,
            long fileSizeBytes,
            long uploadedById) {}

    /** Package error record: {@code rowNo == null} means a mismatch with the format, otherwise a cell error. */
    public record ErrorRow(
            int ordinal,
            String sheet,
            Integer rowNo,
            String columnName,
            String cellValue,
            String code,
            Map<String, Object> params) {}

    /** Package errors: how many were found in total and what was stored. */
    public record ErrorsView(int total, List<ErrorRow> items) {}
}
