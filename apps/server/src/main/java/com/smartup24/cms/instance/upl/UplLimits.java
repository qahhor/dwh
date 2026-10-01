package com.smartup24.cms.instance.upl;

/**
 * File upload limits. They protect the server from files that are too large and are not an industry norm:
 * the values are the same for all instances and are not exposed as organization settings.
 */
public final class UplLimits {

    /**
     * The largest accepted file size in megabytes: an upload limit, not a norm. The product limit of one file
     * (FR-FILE-02): since the apply streams (plan 10/10, item 3.9) the size of a file no longer bounds the heap.
     */
    public static final long MAX_FILE_MEGABYTES = 50;

    /** The largest accepted file size in bytes: an upload limit, not a norm. */
    public static final long MAX_FILE_BYTES = MAX_FILE_MEGABYTES * 1024 * 1024;

    /**
     * The largest number of filled cells in a file: an upload limit, not a norm. Rows are streamed, so it bounds the
     * time of a parse, not memory: a million rows of ten columns fit.
     */
    public static final long MAX_CELLS = 10000000;

    /** How many error records are stored per package: an upload limit, not a norm. */
    public static final int MAX_STORED_ERRORS = 500;

    /** The length to which a cell value is truncated in an error record: an upload limit, not a norm. */
    public static final int MAX_VALUE_LENGTH = 200;

    private UplLimits() {}
}
