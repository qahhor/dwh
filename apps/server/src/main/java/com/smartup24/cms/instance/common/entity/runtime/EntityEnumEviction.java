package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.event.EntityChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps the items of a reference entity fresh (ADR-0032, 4.5; ADR-0025): a change of a reference record the runtime
 * makes — a new item, a renamed, moved, archived or deleted one — clears the reference's cached items on every node, at
 * once and again after the commit, so a read between the two cannot keep the old items for the cache's lifetime.
 */
@Component
public class EntityEnumEviction {

    private final EntityEnums enums;

    public EntityEnumEviction(EntityEnums enums) {
        this.enums = enums;
    }

    @EventListener
    public void changed(EntityChanged change) {
        String code = change.entity();
        if (!enums.isReference(code)) return;
        enums.evict(code);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enums.evict(code);
                }
            });
        }
    }
}
