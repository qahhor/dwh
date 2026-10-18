package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityTitles;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The field that names the records of a served live form (ADR-0041): the system name for formats 1 to 3, else the
 * form's title field or the record's number. The screens name a record by it on its card and in confirmations.
 */
@Component
@Profile("!migrate")
public class StudioTitles implements EntityTitles {
    private final StudioDefinitions definitions;

    public StudioTitles(StudioDefinitions definitions) {
        this.definitions = definitions;
    }

    @Override
    public @Nullable String titleField(EntityDefinition entity) {
        if (!entity.code().startsWith(StudioDefinitions.MODULE + ".")) return null;
        EntityDefinitionSource.Declaration served = definitions.snapshot().get(entity.code());
        return served == null ? null : served.titleField();
    }
}
