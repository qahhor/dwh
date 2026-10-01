package com.smartup24.cms.instance.common.versioning;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import java.util.Optional;

/**
 * The rules of the versioning standard (V102, V108, V109): codes the facade throws and the triggers
 * {@code bump_version()} and {@code deny_update_published()} raise. None is a named constraint: a versions table is
 * the declaring module's, and so are its constraints (ADR-0030).
 */
public enum VersionError implements ConstraintCode {
    STALE_VERSION {
        @Override
        public ConstraintViolationException exception(Throwable cause) {
            return new StaleVersionException();
        }
    },
    FND_VERSION_DRAFT_EXISTS,
    FND_VERSION_UNKNOWN,
    FND_VERSION_NOT_AFTER_PREVIOUS,
    FND_VERSION_GAP,
    FND_VERSION_PUBLISHED_IMMUTABLE,
    FND_VERSION_CONFLICT;

    @Override
    public Optional<String> constraintName() {
        return Optional.empty();
    }

    /** A missing version is not found; every other rule conflicts with the versions already there. */
    @Override
    public ErrorCode errorCode() {
        return this == FND_VERSION_UNKNOWN ? ErrorCode.NOT_FOUND : ErrorCode.CONFLICT;
    }
}
