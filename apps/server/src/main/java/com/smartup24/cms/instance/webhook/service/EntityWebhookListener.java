package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityRemoving;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The webhooks of the entities (ADR-0032, 6.9; plan 10/10, item 5.4): every change the runtime publishes becomes the
 * event {@code <form>.<what>} ({@code notes.updated}, {@code notes.archived}, a record action by its code) for the
 * subscriptions to it, written to {@code kwh_outbox} in the transaction of the change — committed with it, rolled back
 * with it. No module calls the webhooks for its entity. The envelope names the entity, the record, its revision and the
 * changed fields; its {@code data} is the record — with the rows of a document — without any field that needs a right
 * (ADR-0032, 5.2: a subscription has no viewer); a deleted record has no data.
 *
 * <p>A subscription scoped to its creator (ADR-0041, owner decisions of 2026-10-18) receives the event only when its
 * creator may read the record when the event is written: an active user holding the entity's {@code view} right whose
 * data scope holds the record; a deletion only when the creator could read the record just before it went. Anything
 * else is withheld — never queued — and counted by reason in {@code smc.webhook.events.withheld}.
 */
@Component
public class EntityWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(EntityWebhookListener.class);

    /** The transaction resource with the creators who could read each record being deleted. */
    private static final String REMOVALS = EntityWebhookListener.class.getName() + ".removals";

    static final String WITHHELD = "smc.webhook.events.withheld";

    private final WebhookService webhooks;
    private final EntityRegistry entities;
    private final EntityReads records;
    private final MdPermissionService permissions;
    private final @Nullable MeterRegistry meters;

    public EntityWebhookListener(
            WebhookService webhooks,
            EntityRegistry entities,
            EntityReads records,
            MdPermissionService permissions,
            ObjectProvider<MeterRegistry> meters) {
        this.webhooks = webhooks;
        this.entities = entities;
        this.records = records;
        this.permissions = permissions;
        this.meters = meters.getIfAvailable();
    }

    @EventListener
    public void changed(EntityChanged change) {
        Optional<EntityDefinition> entity = entities.find(change.entity());
        if (entity.isEmpty()) return;
        webhooks.publishEvent(
                change.eventName(),
                () -> envelope(entity.get(), change),
                creator -> reads(entity.get(), change, creator));
    }

    /** Before a record goes: which creators of scoped subscriptions to its deletion may read it now. */
    @EventListener
    public void removing(EntityRemoving removal) {
        Optional<EntityDefinition> entity = entities.find(removal.entity());
        if (entity.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        Set<Long> creators = webhooks.scopedCreators(entity.get().form() + "." + EntityEventType.DELETED.wire());
        if (creators.isEmpty()) return;
        Set<Long> readers = new HashSet<>();
        for (Long creator : creators) {
            if (reads(entity.get(), removal.recordId(), creator)) readers.add(creator);
        }
        removals().put(key(removal.entity(), removal.recordId()), readers);
    }

    private boolean reads(EntityDefinition entity, EntityChanged change, long creator) {
        if (change.type() != EntityEventType.DELETED) return reads(entity, change.id(), creator);
        Set<Long> readers = removals().get(key(change.entity(), change.id()));
        boolean read = readers != null && readers.contains(creator);
        if (!read) withheld("not_read_before_deletion", change.entity(), change.id(), creator);
        return read;
    }

    private boolean reads(EntityDefinition entity, long id, long creator) {
        if (!permissions.activeHolder(creator, entity.form(), "view")) {
            withheld("creator_access", entity.code(), id, creator);
            return false;
        }
        if (!records.inScopeOf(entity, id, creator)) {
            withheld("out_of_scope", entity.code(), id, creator);
            return false;
        }
        return true;
    }

    private void withheld(String reason, String entity, long id, long creator) {
        log.debug("webhook_event_withheld reason={} entity={} id={} creator={}", reason, entity, id, creator);
        if (meters != null) meters.counter(WITHHELD, "reason", reason).increment();
    }

    private Map<String, Object> envelope(EntityDefinition entity, EntityChanged change) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", change.eventId().toString());
        envelope.put("type", change.eventName());
        envelope.put("occurredAt", change.occurredAt().toString());
        envelope.put("entity", change.entity());
        envelope.put("recordId", change.id());
        envelope.put("revision", change.revision());
        envelope.put("changedFields", change.changedFields());
        if (change.type() != EntityEventType.DELETED) {
            records.unscoped(entity, change.id())
                    .ifPresent(record -> envelope.put("data", EntityFieldRights.forEveryone(entity, record)));
        }
        return envelope;
    }

    private static String key(String entity, long id) {
        return entity + ":" + id;
    }

    /** The readers of the records being deleted in this transaction; dropped when it ends. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<Long>> removals() {
        Object bound = TransactionSynchronizationManager.getResource(REMOVALS);
        if (bound != null) return (Map<String, Set<Long>>) bound;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return new HashMap<>();
        Map<String, Set<Long>> created = new HashMap<>();
        TransactionSynchronizationManager.bindResource(REMOVALS, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(REMOVALS);
            }
        });
        return created;
    }
}
