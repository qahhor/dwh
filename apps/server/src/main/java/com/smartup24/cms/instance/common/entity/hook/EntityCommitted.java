package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.event.EntityChanged;

/**
 * A committed change of an entity record, for {@code afterCommit} (ADR-0032, 6.5): outside the transaction, after the
 * commit. A side effect that cannot be repeated goes to a queue or an outbox, not here (ADR-0032, 6.4).
 *
 * @param entity the entity
 * @param change what was committed
 */
public record EntityCommitted(EntityDefinition entity, EntityChanged change) {}
