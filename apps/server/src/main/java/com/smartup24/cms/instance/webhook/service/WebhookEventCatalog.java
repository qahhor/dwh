package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.webhook.api.WebhookEventItem;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import com.smartup24.cms.platform.api.entity.workflow.EntityTransition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The events a webhook subscription may name (ADR-0032, 6.9): every event the entities with a table publish through
 * the general runtime — {@code <form>.created}, {@code .updated}, {@code .deleted} by their declared actions,
 * {@code .archived} and {@code .restored} of an archivable one, and the code of each record action and transition.
 * No module publishes webhook events of its own; a subscription naming any other event is refused with 422, so a typo
 * cannot leave it silently waiting for an event that never comes.
 */
@Component
public class WebhookEventCatalog {

    /** The code of a subscription's event the catalog does not hold. */
    public static final String UNKNOWN_EVENT = "unknown_event";

    private final Map<String, WebhookEventItem> byType;

    @Autowired
    public WebhookEventCatalog(EntityRegistry entities) {
        this(entities.all());
    }

    public WebhookEventCatalog(List<EntityDefinition> entities) {
        Map<String, WebhookEventItem> events = new TreeMap<>();
        for (EntityDefinition entity : entities) {
            for (String event : eventsOf(entity)) {
                String type = entity.form() + "." + event;
                events.putIfAbsent(type, new WebhookEventItem(type, entity.code(), entity.form()));
            }
        }
        this.byType = Map.copyOf(events);
    }

    /** Every event, by name. */
    public List<WebhookEventItem> events() {
        return new TreeMap<>(byType).values().stream().toList();
    }

    /** Refuses a list of events with one the catalog does not hold: 422 with the position of each. */
    public void requireKnown(@Nullable List<String> events) {
        if (events == null) return;
        List<FieldErrorItem> unknown = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            String event = events.get(i);
            if (event == null || !byType.containsKey(event)) {
                unknown.add(FieldErrorItem.keyed(
                        "subscribedEvents[" + i + "]",
                        UNKNOWN_EVENT,
                        "error.webhook.event_unknown",
                        Map.of("event", String.valueOf(event))));
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.validation("error.webhook.subscription_invalid", unknown);
        }
    }

    /** What an entity's changes are named after its form: the events its declaration lets the runtime publish. */
    static Set<String> eventsOf(EntityDefinition entity) {
        EntityModel model = entity.model();
        Set<String> events = new LinkedHashSet<>();
        if (model == null) return events;
        for (EntityAction action : entity.actions()) {
            switch (action.code()) {
                case "create" -> events.add(EntityEventType.CREATED.wire());
                case "update" -> events.add(EntityEventType.UPDATED.wire());
                case EntityDefinition.DELETE -> events.add(EntityEventType.DELETED.wire());
                case EntityDefinition.ARCHIVE -> {
                    events.add(EntityEventType.ARCHIVED.wire());
                    events.add(EntityEventType.RESTORED.wire());
                }
                default -> events.add(action.code());
            }
        }
        if (model.workflow() != null) {
            model.workflow().transitions().stream().map(EntityTransition::code).forEach(events::add);
        }
        return events;
    }
}
