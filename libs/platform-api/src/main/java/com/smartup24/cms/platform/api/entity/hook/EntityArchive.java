package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDefinition;

/**
 * An archive or a restore of an entity record as its hooks see it (ADR-0032, 5.4 and 6.5): the record as it is and
 * the state it moves to. The values do not change; a hook that refuses the switch (a system item of a reference that
 * must stay in use) throws an {@link EntityRefusal} and the transaction rolls back.
 *
 * @param entity   the entity
 * @param id       the record
 * @param before   the record before the switch, read-only
 * @param archived true for an archive, false for a restore
 * @param actor    who switches it
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record EntityArchive(
        EntityDefinition entity, long id, EntityValues before, boolean archived, AuditActor actor) {}
