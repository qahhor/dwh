package com.smartup24.cms.instance.units.api;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import java.util.Optional;

/**
 * The rules of the units of measure: the constraints of {@code fnd_units}, {@code fnd_unit_coefficients} and
 * {@code fnd_unit_coefficient_versions} (V103, V107) and the service's own codes. A unique or exclusion constraint
 * conflicts with existing data, a foreign key names something missing or still in use, a check constraint means
 * invalid data (plan 10/10, item 3.1; ADR-0030).
 */
public enum UnitError implements ConstraintCode {
    FND_UNITS_UK_CODE("fnd_units_uk_code", ErrorCode.CODE_ALREADY_EXISTS),
    FND_UNITS_FK_BASE_UNIT("fnd_units_fk_base_unit", ErrorCode.CONFLICT),
    FND_UNITS_CK_CODE("fnd_units_ck_code", ErrorCode.VALIDATION_FAILED),
    FND_UNITS_CK_NAME_UZ("fnd_units_ck_name_uz", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENTS_UK_PAIR("fnd_unit_coefficients_uk_pair", ErrorCode.CONFLICT),
    FND_UNIT_COEFFICIENTS_FK_FROM("fnd_unit_coefficients_fk_from", ErrorCode.CONFLICT),
    FND_UNIT_COEFFICIENTS_FK_TO("fnd_unit_coefficients_fk_to", ErrorCode.CONFLICT),
    FND_UNIT_COEFFICIENTS_CK_DISTINCT("fnd_unit_coefficients_ck_distinct", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENT_VERSIONS_FK_COEFFICIENT("fnd_unit_coefficient_versions_fk_coefficient", ErrorCode.CONFLICT),
    FND_UNIT_COEFFICIENT_VERSIONS_CK_STATUS("fnd_unit_coefficient_versions_ck_status", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENT_VERSIONS_CK_VALID_ORDER(
            "fnd_unit_coefficient_versions_ck_valid_order", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENT_VERSIONS_CK_VERSION_POSITIVE(
            "fnd_unit_coefficient_versions_ck_version_positive", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENT_VERSIONS_CK_FACTOR_POSITIVE(
            "fnd_unit_coefficient_versions_ck_factor_positive", ErrorCode.VALIDATION_FAILED),
    FND_UNIT_COEFFICIENT_VERSIONS_EX_VALID("fnd_unit_coefficient_versions_ex_valid", ErrorCode.CONFLICT),

    /** No unit with the code. */
    FND_UNIT_UNKNOWN(null, ErrorCode.NOT_FOUND),
    /** A unit without a base unit (V107). */
    FND_UNIT_BASE_REQUIRED(null, ErrorCode.VALIDATION_FAILED);

    private final String constraintName;
    private final ErrorCode errorCode;

    UnitError(String constraintName, ErrorCode errorCode) {
        this.constraintName = constraintName;
        this.errorCode = errorCode;
    }

    @Override
    public String module() {
        return "units";
    }

    @Override
    public Optional<String> constraintName() {
        return Optional.ofNullable(constraintName);
    }

    @Override
    public ErrorCode errorCode() {
        return errorCode;
    }
}
