package com.smartup24.cms.instance.fnd.api;

import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Translates PostgreSQL errors into {@link ConstraintViolationException}: by the constraint name (the PostgreSQL
 * driver reports it as {@code ServerErrorMessage.getConstraint()}), by the text of a foundation trigger's
 * {@code raise exception '<code>'}, or, for versions tables ({@link #translatingVersions}), by a violation of their
 * primary key — {@code fnd_version_conflict}. An unrecognised error is returned as it is.
 */
public final class FndSqlErrors {

    private static final Logger log = LoggerFactory.getLogger(FndSqlErrors.class);

    private FndSqlErrors() {}

    /** Runs the action; a database error with a known code becomes an exception carrying that code. */
    public static <T> T translating(SqlAction<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    public static RuntimeException translate(DataAccessException e) {
        SQLException sql = sqlCause(e);
        if (sql == null) {
            return e;
        }
        Optional<ConstraintErrorCode> code = constraintName(sql)
                .flatMap(ConstraintErrorCode::byConstraintName)
                .or(() -> ConstraintErrorCode.byMessage(sql.getMessage()));
        if (code.isEmpty()) {
            return e;
        }
        log.warn("constraint_violation code={} sqlState={}", code.get().code(), sql.getSQLState());
        if (code.get() == ConstraintErrorCode.STALE_VERSION) {
            return new StaleVersionException();
        }
        return new ConstraintViolationException(code.get(), e);
    }

    /**
     * Like {@link #translating(SqlAction)}, but violations of a versions table's own constraints become codes: the
     * primary key {@code <versionsTable>_pkey} (a concurrent createDraft computed the same number) is
     * {@code fnd_version_conflict}; the partial unique index {@code <versionsTable>_draft_uidx} (a second draft of the
     * same header) is {@code fnd_version_draft_exists}.
     */
    public static <T> T translatingVersions(String versionsTable, SqlAction<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            throw translateVersions(versionsTable, e);
        }
    }

    static RuntimeException translateVersions(String versionsTable, DataAccessException e) {
        SQLException sql = sqlCause(e);
        Optional<String> constraint = sql == null ? Optional.empty() : constraintName(sql);
        if (constraint.isPresent()) {
            ConstraintErrorCode code = null;
            if ((versionsTable + "_pkey").equals(constraint.get())) {
                code = ConstraintErrorCode.FND_VERSION_CONFLICT;
            } else if ((versionsTable + "_draft_uidx").equals(constraint.get())) {
                code = ConstraintErrorCode.FND_VERSION_DRAFT_EXISTS;
            }
            if (code != null) {
                log.warn("constraint_violation code={} sqlState={}", code.code(), sql.getSQLState());
                return new ConstraintViolationException(code, e);
            }
        }
        return translate(e);
    }

    private static Optional<String> constraintName(SQLException sql) {
        if (sql instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null) {
            return Optional.ofNullable(pg.getServerErrorMessage().getConstraint());
        }
        return Optional.empty();
    }

    private static SQLException sqlCause(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }

    @FunctionalInterface
    public interface SqlAction<T> {
        T run();
    }
}
