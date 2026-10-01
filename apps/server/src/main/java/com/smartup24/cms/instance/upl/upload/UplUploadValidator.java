package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.FieldErrorItem;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks the fields of a file upload request before a package is created.
 * Collects all errors at once: the user fixes the form in one pass rather than one error at a time.
 */
public final class UplUploadValidator {

    /** The source is not selected or its identifier is not a positive integer. */
    public static final String UPL_PKG_SOURCE_REQUIRED = "UPL_PKG_SOURCE_REQUIRED";
    /** The period start or end is missing, or a date cannot be read as {@code yyyy-MM-dd}. */
    public static final String UPL_PKG_PERIOD_REQUIRED = "UPL_PKG_PERIOD_REQUIRED";
    /** The period start is after its end. */
    public static final String UPL_PKG_PERIOD_ORDER = "UPL_PKG_PERIOD_ORDER";
    /** No file is attached to the request. */
    public static final String UPL_PKG_FILE_REQUIRED = "UPL_PKG_FILE_REQUIRED";
    /** The attached file is empty. */
    public static final String UPL_PKG_FILE_EMPTY = "UPL_PKG_FILE_EMPTY";
    /** The extension of the attached file is not {@code .xlsx}. */
    public static final String UPL_PKG_FILE_NOT_XLSX = "UPL_PKG_FILE_NOT_XLSX";

    private static final String FIELD_SOURCE = "sourceId";
    private static final String FIELD_PERIOD_FROM = "periodFrom";
    private static final String FIELD_PERIOD_TO = "periodTo";
    private static final String FIELD_FILE = "file";

    private static final String XLSX_SUFFIX = ".xlsx";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private UplUploadValidator() {}

    /** All field errors of the upload request; an empty list means the request can be accepted. */
    public static List<FieldErrorItem> validate(
            String sourceId, String periodFrom, String periodTo, boolean filePresent, String fileName, long fileSize) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (!isPositiveNumber(sourceId)) {
            errors.add(FieldErrorItem.keyed(FIELD_SOURCE, UPL_PKG_SOURCE_REQUIRED, "error.upl.field_source_required"));
        }
        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);
        if (from == null) {
            errors.add(periodRequired(FIELD_PERIOD_FROM));
        }
        if (to == null) {
            errors.add(periodRequired(FIELD_PERIOD_TO));
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(FieldErrorItem.keyed(FIELD_PERIOD_FROM, UPL_PKG_PERIOD_ORDER, "error.upl.field_period_order"));
        }
        addFileError(errors, filePresent, fileName, fileSize);
        return List.copyOf(errors);
    }

    private static void addFileError(List<FieldErrorItem> errors, boolean filePresent, String fileName, long fileSize) {
        if (!filePresent) {
            errors.add(FieldErrorItem.keyed(FIELD_FILE, UPL_PKG_FILE_REQUIRED, "error.upl.field_file_required"));
            return;
        }
        if (fileSize == 0) {
            errors.add(FieldErrorItem.keyed(FIELD_FILE, UPL_PKG_FILE_EMPTY, "error.upl.field_file_empty"));
            return;
        }
        if (!isXlsxName(fileName)) {
            errors.add(FieldErrorItem.keyed(FIELD_FILE, UPL_PKG_FILE_NOT_XLSX, "error.upl.field_file_not_xlsx"));
        }
    }

    private static FieldErrorItem periodRequired(String field) {
        return FieldErrorItem.keyed(field, UPL_PKG_PERIOD_REQUIRED, "error.upl.field_period_required");
    }

    private static boolean isPositiveNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(value.strip()) > 0;
        } catch (NumberFormatException notNumber) {
            return false;
        }
    }

    private static boolean isXlsxName(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(XLSX_SUFFIX);
    }

    /** A date strictly as {@code yyyy-MM-dd}; otherwise {@code null}, which the caller turns into a field error. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), DATE);
        } catch (DateTimeParseException notDate) {
            return null;
        }
    }
}
