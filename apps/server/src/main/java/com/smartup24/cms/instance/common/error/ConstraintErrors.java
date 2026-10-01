package com.smartup24.cms.instance.common.error;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Translates PostgreSQL errors into {@link ConstraintViolationException} with one of the codes a module declares: by
 * the constraint name (the PostgreSQL driver reports it as {@code ServerErrorMessage.getConstraint()}) or by the text
 * of a trigger's {@code raise exception '<code>'}. An unrecognised error is returned as it is, so a caller that
 * translates with its own codes around a call of another module still sees what that module left untranslated (plan
 * 10/10, item 4.2).
 */
public final class ConstraintErrors {

    private static final Logger log = LoggerFactory.getLogger(ConstraintErrors.class);

    private ConstraintErrors() {}

    /** The codes of several enums as one list, for a module whose writes meet the rules of more than one owner. */
    public static List<ConstraintCode> codes(ConstraintCode[]... groups) {
        List<ConstraintCode> all = new ArrayList<>();
        for (ConstraintCode[] group : groups) {
            all.addAll(List.of(group));
        }
        return List.copyOf(all);
    }

    /** Runs the action; a database error with one of the codes becomes an exception carrying that code. */
    public static <T> T translating(List<ConstraintCode> codes, SqlAction<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            throw translate(codes, e);
        }
    }

    /** The exception for a database error: one with a known code, or the error itself. */
    public static RuntimeException translate(List<ConstraintCode> codes, DataAccessException e) {
        SQLException sql = sqlCause(e);
        if (sql == null) {
            return e;
        }
        Optional<String> constraint = constraintName(sql);
        Optional<ConstraintCode> code = constraint
                .flatMap(name -> codes.stream()
                        .filter(candidate ->
                                candidate.constraintName().filter(name::equals).isPresent())
                        .findFirst())
                .or(() -> byMessage(codes, sql.getMessage()));
        if (code.isEmpty()) {
            return e;
        }
        log.warn("constraint_violation code={} sqlState={}", code.get().code(), sql.getSQLState());
        return code.get().exception(e);
    }

    /** A logical code (one without a constraint) whose text the server message carries. */
    private static Optional<ConstraintCode> byMessage(List<ConstraintCode> codes, @Nullable String message) {
        if (message == null) {
            return Optional.empty();
        }
        return codes.stream()
                .filter(c -> c.constraintName().isEmpty() && message.contains(c.code()))
                .findFirst();
    }

    /** The constraint the PostgreSQL server named in its error, if any. */
    public static Optional<String> constraintName(SQLException sql) {
        if (sql instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null) {
            return Optional.ofNullable(pg.getServerErrorMessage().getConstraint());
        }
        return Optional.empty();
    }

    /** The first {@link SQLException} in the cause chain, or null. */
    public static @Nullable SQLException sqlCause(@Nullable Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }

    /** A database action whose errors are translated. */
    @FunctionalInterface
    public interface SqlAction<T> {
        T run();
    }
}
