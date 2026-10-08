package com.smartup24.cms.instance.warehouse.api;

import java.util.List;
import java.util.UUID;

/**
 * The only entry point into the {@code raw} layer of the second database. The facade has no update or delete
 * methods: {@code raw} is immutable, and a new version of the data is a new load. Each load has its own partition
 * (plan 10/10, item 7.8); dropping the partition of a failed load is the job of {@code fnd.load_cleanup}.
 */
public interface RawWriter {

    /**
     * Writes the rows of one load in one transaction: all or none.
     *
     * @param loadId       a load version in status {@code pending}; any other is refused
     * @param sourceFileId the framework file ({@code mf_files.id}) the rows were read from
     * @param rows         the rows as read; an exception thrown by the source reaches the caller
     */
    default void write(long loadId, UUID sourceFileId, Iterable<RawRow> rows) {
        copy(loadId, sourceFileId, sink -> rows.forEach(sink));
    }

    /**
     * Streams the rows of one load into raw as the source emits them, in one transaction: all or none. The
     * writer keeps no rows, so the size of a load does not bound it (plan 10/10, item 3.9).
     *
     * @param loadId       a load in status {@code pending}; any other is refused
     * @param sourceFileId the file ({@code mf_files.id}) the rows were read from
     * @param rows         the rows as read; an exception of the source reaches the caller ({@link java.io.IOException}
     *                     as {@link java.io.UncheckedIOException})
     * @return how many rows the database took
     */
    long copy(long loadId, UUID sourceFileId, RawSource rows);

    /** How many rows a load has in raw: a reconciliation counts them and never reads them back. */
    long count(long loadId);

    /** The rows of a load as written, untyped and unchanged. */
    List<RawRow> read(long loadId);
}
