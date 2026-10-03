package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import com.smartup24.cms.platform.api.entity.hook.EntityCommitted;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Steps 13–14 of ADR-0032, 6.3: the event of a change is published in its transaction, so the listeners that write an
 * outbox — the webhooks — commit or roll back with it (ADR-0032, 6.9); after the commit the entity's
 * {@code afterCommit} hook runs, and its failure is logged without changing the answer (ADR-0032, 6.5). A transaction
 * of the idempotency filter is the one that commits: the hook waits for it (ADR-0032, 6.4).
 */
@Component
public class EntityEvents {

    private static final Logger log = LoggerFactory.getLogger(EntityEvents.class);

    private final ApplicationEventPublisher publisher;
    private final EntityRegistry registry;
    private final Clock clock;

    @Autowired
    public EntityEvents(ApplicationEventPublisher publisher, EntityRegistry registry) {
        this(publisher, registry, Clock.systemUTC());
    }

    public EntityEvents(ApplicationEventPublisher publisher, EntityRegistry registry, Clock clock) {
        this.publisher = publisher;
        this.registry = registry;
        this.clock = clock;
    }

    /** Publishes the change in the current transaction and schedules the entity's {@code afterCommit} hook. */
    public EntityChanged changed(
            EntityDefinition entity,
            long id,
            long revision,
            EntityEventType type,
            @Nullable String action,
            List<String> changedFields,
            @Nullable Long actorId) {
        EntityChanged change = new EntityChanged(
                entity.code(),
                entity.form(),
                id,
                revision,
                type,
                action,
                changedFields,
                actorId,
                clock.instant(),
                UUID.randomUUID());
        publisher.publishEvent(change);
        Optional<EntityHooks> hooks = registry.hooks(entity.code());
        if (hooks.isPresent() && TransactionSynchronizationManager.isSynchronizationActive()) {
            EntityHooks hook = hooks.get();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommitted(hook, new EntityCommitted(entity, change));
                }
            });
        }
        return change;
    }

    /** Runs the hook after the commit; a failure cannot undo the committed change, so it is logged (ADR-0032, 6.3). */
    static void afterCommitted(EntityHooks hook, EntityCommitted committed) {
        try {
            hook.afterCommit(committed);
        } catch (RuntimeException failure) {
            log.warn(
                    "entity_after_commit_failed entity={} id={}",
                    committed.change().entity(),
                    committed.change().id(),
                    failure);
        }
    }
}
