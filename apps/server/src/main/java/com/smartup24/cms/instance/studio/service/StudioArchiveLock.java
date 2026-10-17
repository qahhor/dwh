package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityWriteLock;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The records of an archived live form are read-only (ADR-0041): its list, records, history and export stay open, and
 * every write answers 409 {@code error.studio.archived}. The state comes from the catalog snapshot of the request.
 */
@Component
@Profile("!migrate")
public class StudioArchiveLock implements EntityWriteLock {
    private final StudioDefinitions definitions;

    public StudioArchiveLock(StudioDefinitions definitions) {
        this.definitions = definitions;
    }

    @Override
    public @Nullable ApiException refusal(EntityDefinition entity) {
        if (!entity.code().startsWith(StudioDefinitions.MODULE + ".") || !definitions.archived(entity.code())) {
            return null;
        }
        return new ApiException(ErrorCode.CONFLICT, "error.studio.archived");
    }
}
