package com.smartup24.cms.instance.common.versioning;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintErrors;
import com.smartup24.cms.instance.common.error.ConstraintErrors.SqlAction;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Translates the errors of a write to a versions table: the codes of the versioning standard and of the audit trigger
 * ({@link #CODES}), the codes the declaring modules publish ({@code ConstraintCodes} beans), and the table's own
 * primary key and draft index. A violation of the primary key {@code <versionsTable>_pkey} (a concurrent createDraft
 * computed the same number) is {@code fnd_version_conflict}; of the partial unique index
 * {@code <versionsTable>_draft_uidx} (a second draft of the same header) is {@code fnd_version_draft_exists}.
 * Anything else is returned as it is.
 */
public final class VersionErrors {

    /** The codes a write to a versions table may meet whatever module declared the table. */
    public static final List<ConstraintCode> CODES = ConstraintErrors.codes(VersionError.values(), ActorError.values());

    private static final Logger log = LoggerFactory.getLogger(VersionErrors.class);

    private VersionErrors() {}

    /** Runs a write of the standard's own columns with the given codes; a known error becomes its exception. */
    static <T> T translating(List<ConstraintCode> codes, SqlAction<T> action) {
        return ConstraintErrors.translating(codes, action);
    }

    /** Runs a write to the versions table, translating its primary key and draft index as well. */
    public static <T> T translatingVersions(String versionsTable, SqlAction<T> action) {
        return translatingVersions(versionsTable, CODES, action);
    }

    static <T> T translatingVersions(String versionsTable, List<ConstraintCode> codes, SqlAction<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            throw translateVersions(versionsTable, codes, e);
        }
    }

    static RuntimeException translateVersions(String versionsTable, List<ConstraintCode> codes, DataAccessException e) {
        SQLException sql = ConstraintErrors.sqlCause(e);
        if (sql == null) {
            return e;
        }
        Optional<String> constraint = ConstraintErrors.constraintName(sql);
        if (constraint.isPresent()) {
            VersionError code = null;
            if ((versionsTable + "_pkey").equals(constraint.get())) {
                code = VersionError.FND_VERSION_CONFLICT;
            } else if ((versionsTable + "_draft_uidx").equals(constraint.get())) {
                code = VersionError.FND_VERSION_DRAFT_EXISTS;
            }
            if (code != null) {
                log.warn("constraint_violation code={} sqlState={}", code.code(), sql.getSQLState());
                return new ConstraintViolationException(code, e);
            }
        }
        return ConstraintErrors.translate(codes, e);
    }
}
