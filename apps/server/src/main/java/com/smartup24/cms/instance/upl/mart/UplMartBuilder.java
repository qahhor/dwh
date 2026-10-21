package com.smartup24.cms.instance.upl.mart;

import com.smartup24.cms.instance.units.api.CoefficientMissingException;
import com.smartup24.cms.instance.units.api.Units;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplFormatRepository;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Materializes accepted, unit-converted and normalized rows of an applied {@code upl} load into {@code mart.rows} in
 * {@code pg-dwh} (plan 10/10, Constructor stage 3.3).
 */
@Component
public class UplMartBuilder {

    /** Loads up to this size are materialized eagerly on apply; larger loads are materialized on demand. */
    static final int EAGER_ROW_LIMIT = 50_000;

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE =
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final Logger log = LoggerFactory.getLogger(UplMartBuilder.class);

    private final UplFormatRepository formats;
    private final WarehouseLoads loads;
    private final RawWriter raw;
    private final WarehouseMarts marts;
    private final Units units;

    public UplMartBuilder(
            UplFormatRepository formats, WarehouseLoads loads, RawWriter raw, WarehouseMarts marts, Units units) {
        this.formats = formats;
        this.loads = loads;
        this.raw = raw;
        this.marts = marts;
        this.units = units;
    }

    /** Eagerly materializes a newly applied package when within {@link #EAGER_ROW_LIMIT}. */
    public void publishAfterApply(PackageRow row) {
        if (row.loadId() == null || row.rowsTotal() == null || row.rowsTotal() > EAGER_ROW_LIMIT) {
            return;
        }
        try {
            Optional<FormatVersion> version = formats.findVersion(row.sourceId(), row.formatVersion());
            if (version.isEmpty()) return;
            List<Long> activeLoadIds = loads.appliedLoadIds(row.sourceCode());
            List<MartRow> normalized = buildMartRows(row.loadId(), row.periodFrom(), version.get());
            marts.publishLoad(
                    row.sourceCode(), row.loadId(), row.periodFrom(), row.periodTo(), normalized, activeLoadIds);
        } catch (RuntimeException deferred) {
            log.warn("upl_mart_eager_publish_deferred package={} load={}", row.publicId(), row.loadId(), deferred);
        }
    }

    /** Ensures all active applied loads of a source are materialized in {@code mart.rows} before reporting. */
    public void ensureMaterialized(SourceSummary source, FormatVersion defaultVersion, List<Long> activeLoadIds) {
        if (activeLoadIds.isEmpty()) return;
        Set<Long> ready = Set.copyOf(marts.materializedLoadIds(source.code(), activeLoadIds));
        for (Long loadId : activeLoadIds) {
            if (ready.contains(loadId)) continue;
            Optional<WarehouseLoad> load = loads.find(loadId);
            if (load.isEmpty()) continue;
            WarehouseLoad found = load.get();
            FormatVersion version = resolveVersion(source.id(), found.formatVersion(), defaultVersion);
            List<MartRow> normalized = buildMartRows(loadId, found.periodFrom(), version);
            marts.publishLoad(source.code(), loadId, found.periodFrom(), found.periodTo(), normalized, activeLoadIds);
        }
    }

    private FormatVersion resolveVersion(long sourceId, String formatVersion, FormatVersion fallback) {
        if (formatVersion == null || formatVersion.isBlank()) return fallback;
        String digits = formatVersion.startsWith("v") || formatVersion.startsWith("V")
                ? formatVersion.substring(1)
                : formatVersion;
        try {
            int versionNo = Integer.parseInt(digits);
            return formats.findVersion(sourceId, versionNo).orElse(fallback);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    List<MartRow> buildMartRows(long loadId, LocalDate periodFrom, FormatVersion version) {
        Map<String, List<Column>> columnsBySheet = new LinkedHashMap<>();
        for (Sheet sheet : version.sheets()) {
            columnsBySheet.put(sheet.sheetName(), sheet.columns());
        }
        List<Column> firstSheet = version.sheets().isEmpty()
                ? List.of()
                : version.sheets().getFirst().columns();
        List<RawRow> rawRows = raw.read(loadId);
        List<MartRow> accepted = new ArrayList<>(rawRows.size());
        for (RawRow row : rawRows) {
            List<Column> columns = columnsBySheet.getOrDefault(row.sheet(), firstSheet);
            normalizeRow(row, columns, periodFrom).ifPresent(accepted::add);
        }
        return accepted;
    }

    private Optional<MartRow> normalizeRow(RawRow row, List<Column> columns, LocalDate periodFrom) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Column col : columns) {
            Object rawVal = row.fields() == null ? null : row.fields().get(col.targetField());
            String text = rawVal == null ? null : rawVal.toString().strip();
            if (text == null || text.isEmpty()) {
                if (col.required()) return Optional.empty();
                continue;
            }
            Optional<Object> converted = convertCell(col, text, periodFrom);
            if (converted.isEmpty()) {
                return Optional.empty();
            }
            normalized.put(col.targetField(), converted.get());
        }
        return Optional.of(new MartRow(row.rowNo(), row.sheet(), normalized));
    }

    private Optional<Object> convertCell(Column col, String text, LocalDate periodFrom) {
        return switch (col.dataType()) {
            case TEXT, REF_CODE -> Optional.of(text);
            case OBJECT_KEY -> convertKey(col, text);
            case INTEGER -> convertInteger(col, text, periodFrom);
            case NUMBER -> convertNumber(col, text, periodFrom);
            case DATE -> convertDate(text);
        };
    }

    private static Optional<Object> convertKey(Column col, String text) {
        String key = text;
        BigDecimal number = parseDecimal(text, false);
        if (number != null && !text.startsWith("0")) {
            if (number.stripTrailingZeros().scale() > 0) return Optional.empty();
            key = number.toBigInteger().toString();
        }
        String padded = padKey(key, col.keyPadLength(), col.keyPadMax());
        if (col.keyMask() != null && !Pattern.matches(col.keyMask(), padded)) {
            return Optional.empty();
        }
        return Optional.of(padded);
    }

    private Optional<Object> convertInteger(Column col, String text, LocalDate periodFrom) {
        BigDecimal number = parseDecimal(text, false);
        if (number == null || number.stripTrailingZeros().scale() > 0) {
            return Optional.empty();
        }
        return Optional.of(applyUnit(number.stripTrailingZeros(), col, periodFrom));
    }

    private Optional<Object> convertNumber(Column col, String text, LocalDate periodFrom) {
        BigDecimal number = parseDecimal(text, true);
        if (number == null) {
            return Optional.empty();
        }
        return Optional.of(applyUnit(number.stripTrailingZeros(), col, periodFrom));
    }

    private BigDecimal applyUnit(BigDecimal value, Column col, LocalDate periodFrom) {
        String from = col.sourceUnit();
        String base = col.baseUnit();
        if (from == null || from.isBlank() || from.equals(base)) {
            return value;
        }
        try {
            return units.toBase(value, from, periodFrom).value().stripTrailingZeros();
        } catch (CoefficientMissingException missing) {
            log.warn("upl_mart_coefficient_missing from={} base={} period={}", from, base, periodFrom);
            return value;
        }
    }

    private static Optional<Object> convertDate(String text) {
        LocalDate iso = tryDate(text, ISO_DATE);
        if (iso != null) return Optional.of(iso.toString());
        LocalDate dotted = tryDate(text, DOTTED_DATE);
        if (dotted != null) return Optional.of(dotted.toString());
        BigDecimal serial = parseDecimal(text, false);
        if (serial != null) {
            return Optional.of(EXCEL_EPOCH.plusDays(serial.longValue()).toString());
        }
        return Optional.empty();
    }

    private static LocalDate tryDate(String text, DateTimeFormatter formatter) {
        try {
            return LocalDate.parse(text, formatter);
        } catch (DateTimeParseException wrong) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String text, boolean allowComma) {
        String normalized = allowComma ? text.replace(',', '.') : text;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    private static String padKey(String key, Integer padLength, Integer padMax) {
        if (padLength == null || key.length() >= padLength) return key;
        int missing = padLength - key.length();
        if (padMax != null && missing > padMax) return key;
        return "0".repeat(missing) + key;
    }
}
