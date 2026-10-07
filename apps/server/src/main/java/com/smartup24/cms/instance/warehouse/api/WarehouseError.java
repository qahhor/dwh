package com.smartup24.cms.instance.warehouse.api;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import java.util.Optional;

/**
 * The rules of the warehouse: the constraints of the load ledger {@code fnd_loads} and {@code fnd_load_log} (V104,
 * V106) and the codes of the facades of the second database (plan 10/10, item 3.1; ADR-0030).
 */
public enum WarehouseError implements ConstraintCode {
    FND_LOADS_UK_PACKAGE_REF("fnd_loads_uk_package_ref", ErrorCode.CONFLICT),
    FND_LOADS_CK_STATUS("fnd_loads_ck_status", ErrorCode.VALIDATION_FAILED),
    FND_LOADS_CK_ROWS("fnd_loads_ck_rows", ErrorCode.VALIDATION_FAILED),
    FND_LOADS_CK_PERIOD("fnd_loads_ck_period", ErrorCode.VALIDATION_FAILED),
    FND_LOADS_FK_SUPERSEDED_BY("fnd_loads_fk_superseded_by", ErrorCode.CONFLICT),
    FND_LOAD_LOG_FK_LOAD("fnd_load_log_fk_load", ErrorCode.CONFLICT),
    FND_LOAD_LOG_CK_FILE_SHA("fnd_load_log_ck_file_sha", ErrorCode.VALIDATION_FAILED),
    FND_LOAD_LOG_CK_ACTOR("fnd_load_log_ck_actor", ErrorCode.VALIDATION_FAILED),

    /** A load moved from a status that does not allow it. */
    FND_LOAD_STATUS_TRANSITION(null, ErrorCode.STATUS_TRANSITION_FORBIDDEN),
    /** The package log only grows: raised by {@code fnd_load_log_append_only()}. */
    FND_LOAD_LOG_APPEND_ONLY(null, ErrorCode.CONFLICT),
    /** A read outside the marts and the cache, or with a name that is not one. */
    DWH_READ_FORBIDDEN(null, ErrorCode.FORBIDDEN),
    /** The second database does not answer. */
    DWH_UNAVAILABLE(null, ErrorCode.SERVICE_UNAVAILABLE);

    private final String constraintName;
    private final ErrorCode errorCode;

    WarehouseError(String constraintName, ErrorCode errorCode) {
        this.constraintName = constraintName;
        this.errorCode = errorCode;
    }

    @Override
    public String module() {
        return "warehouse";
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
