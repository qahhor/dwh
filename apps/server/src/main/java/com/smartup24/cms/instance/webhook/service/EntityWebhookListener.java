package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The webhooks of the entities (ADR-0032, 6.9; plan 10/10, item 5.4): every change the runtime publishes becomes the
 * event {@code <form>.<what>} ({@code notes.updated}, {@code notes.archived}, a record action by its code) for the
 * subscriptions to it, written to {@code kwh_outbox} in the transaction of the change — committed with it, rolled back
 * with it. No module calls the webhooks for its entity. The envelope names the entity, the record, its revision and the
 * changed fields; its {@code data} is the record without any field that needs a right (ADR-0032, 5.2: a subscription
 * has no viewer), read without the scope of a viewer; a deleted record has no data.
 */
@Component
public class EntityWebhookListener {

    private final WebhookService webhooks;
    private final EntityRegistry entities;
    private final EntityReads records;

    public EntityWebhookListener(WebhookService webhooks, EntityRegistry entities, EntityReads records) {
        this.webhooks = webhooks;
        this.entities = entities;
        this.records = records;
    }

    @EventListener
    public void changed(EntityChanged change) {
        Optional<EntityDefinition> entity = entities.find(change.entity());
        if (entity.isEmpty()) return;
        webhooks.publishEvent(change.eventName(), () -> envelope(entity.get(), change));
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
}
