package com.smartup24.cms.instance.report.imports;

/**
 * A problem of a whole import file (ADR-0032, 10.1): the import ends failed with the code, the journal keeps the
 * problems found, and no retry would change it. The codes are what the client shows ({@code errorCode}).
 */
final class ImportFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The file cannot be read as xlsx. */
    static final String UNREADABLE = "IMPORT_UNREADABLE";

    /** The second row names a column the person may not fill, twice, or none. */
    static final String STRUCTURE = "IMPORT_STRUCTURE";

    /** The file has no data row. */
    static final String EMPTY = "IMPORT_EMPTY";

    /** The file has more data rows than an import takes. */
    static final String TOO_MANY_ROWS = "IMPORT_TOO_MANY_ROWS";

    /** The person may no longer import into the entity, or is blocked. */
    static final String FORBIDDEN = "IMPORT_FORBIDDEN";

    /** The job failed for another reason; the server's log has it. */
    static final String FAILED = "IMPORT_FAILED";

    private final String code;

    ImportFailure(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
