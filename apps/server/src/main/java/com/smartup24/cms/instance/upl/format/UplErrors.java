package com.smartup24.cms.instance.upl.format;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.fnd.api.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.api.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.api.StaleVersionException;
import org.springframework.dao.DataAccessException;

/** Maps foundation and database errors to an API response per the "Errors" table of the file format contract. */
public final class UplErrors {

    private static final String NOT_DRAFT_DB_ERROR = "upl_format_not_draft";

    private UplErrors() {}

    /** Turns a known error into an {@link ApiException}; returns an unknown one as the same object. */
    public static RuntimeException toApi(RuntimeException e) {
        if (e instanceof StaleVersionException) {
            return ApiException.conflict(ErrorCode.CONFLICT, "error.upl.stale_version");
        }
        if (e instanceof ConstraintViolationException cve) {
            return fromConstraint(cve);
        }
        if (e instanceof DataAccessException && chainContains(e, NOT_DRAFT_DB_ERROR)) {
            return ApiException.conflict(ErrorCode.CONFLICT, "error.upl.format_not_draft");
        }
        return e;
    }

    static boolean chainContains(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeException fromConstraint(ConstraintViolationException e) {
        ConstraintErrorCode code = e.code();
        if (code == ConstraintErrorCode.FND_VERSION_DRAFT_EXISTS || code == ConstraintErrorCode.FND_VERSION_CONFLICT) {
            return ApiException.conflict(ErrorCode.CONFLICT, "error.upl.fnd_version_draft_exists");
        }
        if (code == ConstraintErrorCode.FND_VERSION_NOT_AFTER_PREVIOUS) {
            return ApiException.conflict(ErrorCode.CONFLICT, "error.upl.fnd_version_not_after_previous");
        }
        if (code == ConstraintErrorCode.FND_VERSION_UNKNOWN) {
            return ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.fnd_version_unknown");
        }
        return e;
    }
}
