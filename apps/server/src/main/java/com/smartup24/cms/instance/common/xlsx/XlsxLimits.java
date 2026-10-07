package com.smartup24.cms.instance.common.xlsx;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The bounds an uploaded xlsx workbook must stay within before it is read (plan 10/10, item 7.6). A value left
 * out, zero or negative takes the default. The defaults fit the largest legitimate upload: a 50 MiB file
 * (FR-FILE-02) of ten million filled cells unpacks to well under a gigabyte, while a zip bomb exceeds the ratio or the
 * unpacked size long before it is inflated whole.
 *
 * @param maxUnpackedSize the most bytes all entries together may inflate to
 * @param maxEntries the most entries the zip may have
 * @param maxCompressionRatio the most an entry larger than {@link #RATIO_FLOOR_BYTES} may inflate per compressed byte
 * @param maxSharedStrings the most shared strings, counted and declared
 * @param maxSharedStringsSize the most bytes the shared strings part may inflate to
 * @param maxRows the highest row number of a sheet
 * @param maxColumns the highest column number of a sheet
 */
@ConfigurationProperties(prefix = "smc.uploads.xlsx")
public record XlsxLimits(
        @Nullable DataSize maxUnpackedSize,
        int maxEntries,
        int maxCompressionRatio,
        int maxSharedStrings,
        @Nullable DataSize maxSharedStringsSize,
        int maxRows,
        int maxColumns) {

    /** Entries that inflate to less than this are never refused for their ratio: tiny XML parts compress very well. */
    public static final long RATIO_FLOOR_BYTES = 1024L * 1024;

    static final DataSize DEFAULT_MAX_UNPACKED_SIZE = DataSize.ofGigabytes(1);
    static final int DEFAULT_MAX_ENTRIES = 1_000;
    static final int DEFAULT_MAX_COMPRESSION_RATIO = 200;
    static final int DEFAULT_MAX_SHARED_STRINGS = 5_000_000;
    static final DataSize DEFAULT_MAX_SHARED_STRINGS_SIZE = DataSize.ofMegabytes(256);
    /** The last row of an Excel sheet. */
    static final int DEFAULT_MAX_ROWS = 1_048_576;
    /** The last column of an Excel sheet, XFD. */
    static final int DEFAULT_MAX_COLUMNS = 16_384;

    public XlsxLimits {
        maxUnpackedSize = positive(maxUnpackedSize, DEFAULT_MAX_UNPACKED_SIZE);
        maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        maxCompressionRatio = maxCompressionRatio > 0 ? maxCompressionRatio : DEFAULT_MAX_COMPRESSION_RATIO;
        maxSharedStrings = maxSharedStrings > 0 ? maxSharedStrings : DEFAULT_MAX_SHARED_STRINGS;
        maxSharedStringsSize = positive(maxSharedStringsSize, DEFAULT_MAX_SHARED_STRINGS_SIZE);
        maxRows = maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
        maxColumns = maxColumns > 0 ? maxColumns : DEFAULT_MAX_COLUMNS;
    }

    /** The default bounds, for readers built outside the application context. */
    public static XlsxLimits defaults() {
        return new XlsxLimits(null, 0, 0, 0, null, 0, 0);
    }

    long unpackedBytes() {
        return positive(maxUnpackedSize, DEFAULT_MAX_UNPACKED_SIZE).toBytes();
    }

    long sharedStringsBytes() {
        return positive(maxSharedStringsSize, DEFAULT_MAX_SHARED_STRINGS_SIZE).toBytes();
    }

    private static DataSize positive(@Nullable DataSize value, DataSize fallback) {
        return value == null || value.toBytes() <= 0 ? fallback : value;
    }
}
