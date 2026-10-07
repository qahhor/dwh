package com.smartup24.cms.instance.common.actor;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import java.util.Optional;

/** The rule of the audit trigger: a change of an audited table names its actor (V100, ADR-0030). */
public enum ActorError implements ConstraintCode {

    /** No numeric {@code app.user_id} of an existing user: raised by {@code fnd_audit_trigger}. */
    AUDIT_ACTOR_MISSING;

    @Override
    public String module() {
        return "actor";
    }

    @Override
    public Optional<String> constraintName() {
        return Optional.empty();
    }

    /** The actor is set by the calling code, never by the request: a missing one is a bug. */
    @Override
    public ErrorCode errorCode() {
        return ErrorCode.INTERNAL_ERROR;
    }
}
