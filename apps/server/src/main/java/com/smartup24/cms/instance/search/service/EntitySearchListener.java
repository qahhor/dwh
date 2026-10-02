package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.entity.event.EntityChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The search hears the entity runtime (ADR-0032, 6.9 and 10.3; plan 10/10, item 5.8): every change of a record of an
 * entity with the SEARCH capability — created, changed, archived, restored, deleted or an action — gives the record a
 * new projection version in the transaction of the change, so it commits or rolls back with it; the delivery then builds
 * the document again from the declaration, or removes it when the search no longer finds the record. No module calls
 * the search for its entity.
 */
@Component
public class EntitySearchListener {

    private final SearchEntities entities;
    private final SearchChangePublisher publisher;

    public EntitySearchListener(SearchEntities entities, SearchChangePublisher publisher) {
        this.entities = entities;
        this.publisher = publisher;
    }

    @EventListener
    public void changed(EntityChanged change) {
        if (entities.find(change.entity()).isPresent()) {
            publisher.changed(change.entity(), change.id());
        }
    }
}
