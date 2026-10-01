package com.smartup24.cms.instance.upl.parse;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing a package file, entirely in memory: either "verified" with row counters,
 * or "rejected" with a reason. There are as many error records as may be stored,
 * while {@code errorsTotal} counts all that were found.
 */
public record UplParseResult(
        Outcome outcome,
        String rejectCode,
        Map<String, Object> rejectParams,
        Integer rowsTotal,
        Integer rowsAccepted,
        Integer rowsRejected,
        int errorsTotal,
        List<ErrorRecord> errors) {

    /** Parse outcome: the file is verified or rejected by the system. */
    public enum Outcome {
        VERIFIED,
        REJECTED
    }

    /** Error record: {@code rowNo == null} means a mismatch with the format, otherwise a cell error. */
    public record ErrorRecord(
            String sheet,
            Integer rowNo,
            String columnName,
            String cellValue,
            String code,
            Map<String, Object> params) {}

    /** The file is verified: "accepted" = total minus rows with errors. */
    public static UplParseResult verified(int total, int rejected, int errorsTotal, List<ErrorRecord> errors) {
        return new UplParseResult(
                Outcome.VERIFIED, null, null, total, total - rejected, rejected, errorsTotal, List.copyOf(errors));
    }

    /** The file is rejected by the system: row counters are not filled in. */
    public static UplParseResult rejected(
            String code, Map<String, Object> params, int errorsTotal, List<ErrorRecord> errors) {
        return new UplParseResult(
                Outcome.REJECTED, code, Map.copyOf(params), null, null, null, errorsTotal, List.copyOf(errors));
    }
}
