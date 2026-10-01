package com.smartup24.cms.instance.common.web;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Optimistic locking of record changes (plan 10/10, item 3.6, ADR-0024). A client changes a record from the revision
 * it read and names it: {@code If-Match: "<revision>"} (the {@code ETag} of the answer), or the {@code expectedRevision}
 * of the body where the API had one. A change that names none is 428; a change from a revision that is no longer the
 * record's is 409, and the second of two concurrent saves never overwrites the first.
 */
public final class Revisions {

    public static final String IF_MATCH = "If-Match";

    private static final Pattern TAG = Pattern.compile("^(?:W/)?\"?(\\d{1,18})\"?$");

    private Revisions() {}

    /** The revision the change is made from, from {@code If-Match}. */
    public static long required(@Nullable String ifMatch) {
        return required(ifMatch, null);
    }

    /** The revision from {@code If-Match}, or from the body when the header is absent. */
    public static long required(@Nullable String ifMatch, @Nullable Long fromBody) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            Matcher tag = TAG.matcher(ifMatch.trim());
            if (!tag.matches()) {
                throw ApiException.validation(
                        "error.common.if_match_invalid",
                        List.of(FieldErrorItem.keyed(IF_MATCH, "IF_MATCH_INVALID", "error.common.if_match_invalid")));
            }
            return Long.parseLong(tag.group(1));
        }
        if (fromBody != null) {
            return fromBody;
        }
        throw missing();
    }

    /**
     * The revision from {@code If-Match}, or {@code null} when the header is absent: for a {@code PUT} that creates the
     * record when it does not exist yet, and replaces it from the named revision when it does.
     */
    public static @Nullable Long optional(@Nullable String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : required(ifMatch);
    }

    /** The answer to a change of an existing record that named no revision (428). */
    public static ApiException missing() {
        return new ApiException(ErrorCode.PRECONDITION_REQUIRED, "error.common.precondition_required");
    }

    /** The {@code ETag} value of a revision. */
    public static String etag(long revision) {
        return "\"" + revision + "\"";
    }

    /** The answer to a change that matched no row of a record that still exists. */
    public static ApiException conflict() {
        return ApiException.conflict(ErrorCode.REVISION_CONFLICT, "error.common.revision_conflict");
    }
}
