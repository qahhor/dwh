package com.smartup24.cms.instance.fnd.dwh;

import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;

/**
 * Вторая база (pg-dwh) недоступна (11 п.11; 02 п.18; AC-36). Пустой список и {@code null}
 * вместо данных не возвращаются никогда: вызывающая транзакция OLTP обязана откатиться.
 */
public class DwhUnavailableException extends ConstraintViolationException {

    public DwhUnavailableException(Throwable cause) {
        super(ConstraintErrorCode.DWH_UNAVAILABLE, cause);
    }
}
