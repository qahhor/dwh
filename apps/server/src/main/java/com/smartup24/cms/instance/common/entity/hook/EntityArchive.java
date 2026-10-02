package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.entity.EntityDefinition;

/**
 * An archive or a restore of an entity record as its hooks see it (ADR-0032, 5.4 and 6.5): the record as it is and
 * the state it moves to. The values do not change; a hook that refuses the switch (a system item of a reference that
 * must stay in use) throws an {@code ApiException} and the transaction rolls back.
 *
 * @param entity   the entity
 * @param id       the record
 * @param before   the record before the switch, read-only
 * @param archived true for an archive, false for a restore
 * @param actor    who switches it
 */
public record EntityArchive(
        EntityDefinition entity, long id, EntityValues before, boolean archived, AuditActor actor) {}
