package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.entity.EntityDefinition;

/**
 * A delete of an entity record as its hooks see it (ADR-0032, 6.5): the record as it was. A hook that refuses the
 * delete throws an {@code ApiException}; the transaction rolls back.
 *
 * @param entity the entity
 * @param id     the record
 * @param before the record before the delete, read-only
 * @param actor  who deletes it
 */
public record EntityDelete(EntityDefinition entity, long id, EntityValues before, AuditActor actor) {}
