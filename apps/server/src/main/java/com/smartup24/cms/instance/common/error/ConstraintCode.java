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
 * <p>The message key belongs to the module that declares the code: {@code error.<module>.<code>} (ADR-0021,
 * ADR-0030), for example {@code error.units.fnd_units_uk_code}.
 */
public interface ConstraintCode {

    /** The enum constant's name; an enum implements it by itself. */
    String name();

    /** The database constraint name; empty for a logical code. */
    Optional<String> constraintName();

    /** The owner of the code as it appears in the message key: {@code jobs}, {@code units}, {@code versioning}. */
    String module();

    /** The API response code of the violation. */
    ErrorCode errorCode();

    /** The code in lower case, as it appears in exceptions, trigger messages and logs. */
    default String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The message key in the i18n catalogs: {@code error.<module>.<code>}. */
    default String messageKey() {
        return "error." + module() + "." + code();
    }

    /** The exception that reports this code; a code with an exception type of its own overrides it. */
    default ConstraintViolationException exception(Throwable cause) {
        return new ConstraintViolationException(this, cause);
    }
}
