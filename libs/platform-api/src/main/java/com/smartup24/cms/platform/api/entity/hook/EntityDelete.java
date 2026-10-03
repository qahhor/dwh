package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDefinition;

/**
 * A delete of an entity record as its hooks see it (ADR-0032, 6.5): the record as it was. A hook that refuses the
 * delete throws an {@link EntityRefusal}; the transaction rolls back.
 *
 * @param entity the entity
 * @param id     the record
 * @param before the record before the delete, read-only
 * @param actor  who deletes it
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record EntityDelete(EntityDefinition entity, long id, EntityValues before, AuditActor actor) {}
