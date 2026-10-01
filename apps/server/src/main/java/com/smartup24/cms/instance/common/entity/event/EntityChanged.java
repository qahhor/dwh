package com.smartup24.cms.instance.common.entity.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An entity record changed (ADR-0032, 6.9; plan 10/10, item 5.4). The runtime publishes it once per change, in the
 * transaction of the change (step 13 of ADR-0032, 6.3): a listener that writes an outbox row — the webhooks — commits
 * or rolls back with the change; a {@code @TransactionalEventListener(AFTER_COMMIT)} sees only what was committed.
 *
 * @param entity        the entity's code ({@code ms.notes})
 * @param form          the form of the entity's right ({@code notes}): the first part of the event's name
 * @param id            the record
 * @param revision      the record's revision after the change; the last one it had for a delete
 * @param type          what happened
 * @param action        the code of the record action, for {@link EntityEventType#ACTION}; null otherwise
 * @param changedFields the keys of the fields the change wrote, in declaration order
 * @param actorId       who changed it, or null for the system
 * @param occurredAt    when
 * @param eventId       the event's own id, the {@code id} of its webhook envelope
 */
public record EntityChanged(
        String entity,
        String form,
        long id,
        long revision,
        EntityEventType type,
        @Nullable String action,
        List<String> changedFields,
        @Nullable Long actorId,
        Instant occurredAt,
        UUID eventId) {

    public EntityChanged {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(type, "type");
        changedFields = List.copyOf(changedFields);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventId, "eventId");
        if ((type == EntityEventType.ACTION) != (action != null)) {
            throw new IllegalArgumentException("An action event names its action, and only it");
        }
    }

    /** The event's name in a webhook ({@code notes.updated}, {@code sales.orders.post}): the right's form and what. */
    public String eventName() {
        return form + "." + (action != null ? action : type.wire());
    }
}
