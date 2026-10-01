package com.smartup24.cms.instance.warehouse.api;

import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.common.error.TransientFailure;

/**
 * The second database (pg-dwh) is unavailable. An empty list or {@code null} is never returned in place of data:
 * the calling OLTP transaction must roll back.
 */
public class WarehouseUnavailableException extends ConstraintViolationException implements TransientFailure {

    public WarehouseUnavailableException(Throwable cause) {
        super(WarehouseError.DWH_UNAVAILABLE, cause);
    }
}
