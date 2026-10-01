package com.smartup24.cms.instance.common.error;

import com.smartup24.cms.core.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;

/**
 * A database rule a module translates into the error model: a named constraint of one of its tables
 * ({@code <table>_(uk|fk|ck|ex)_<suffix>}, ADR-0020) or a logical code that a trigger raises or a service throws.
 * Each module that owns such tables declares its codes as an enum implementing this interface, and
 * {@link ConstraintErrors} turns a database error into a {@link ConstraintViolationException} with the code (plan
 * 10/10, items 3.1 and 4.2).
 *
 * <p>The codes of the data modules split out of the former foundation keep the catalog family
 * {@value #FOUNDATION_KEYS}: the key is part of the response ({@code messageKey}) and its texts are already in every
 * catalog, so the split changes no key (ADR-0030).
 */
public interface ConstraintCode {

    /** The catalog family of the codes of the jobs, warehouse, units, versioning and actor rules (ADR-0030). */
    String FOUNDATION_KEYS = "error.fnd.";

    /** The enum constant's name; an enum implements it by itself. */
    String name();

    /** The database constraint name; empty for a logical code. */
    Optional<String> constraintName();

    /** The API response code of the violation. */
    ErrorCode errorCode();

    /** The code in lower case, as it appears in exceptions, trigger messages and logs. */
    default String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The message key in the i18n catalogs: {@code error.fnd.<code>}. */
    default String messageKey() {
        return FOUNDATION_KEYS + code();
    }

    /** The exception that reports this code; a code with an exception type of its own overrides it. */
    default ConstraintViolationException exception(Throwable cause) {
        return new ConstraintViolationException(this, cause);
    }
}
