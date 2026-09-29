package com.smartup24.cms.instance.common.bulk;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where one item of a bulk action runs (plan 10/10, item 3.12). A request with an Idempotency-Key runs in one
 * transaction; an item that fails would mark it rollback-only and take the other items down with it. Each item
 * therefore runs in a savepoint (NESTED): a failure rolls back that item alone and clears the mark. Without an outer
 * transaction NESTED starts one per item, as the single operation would.
 */
@Component
public class BulkItemScope {

    private final TransactionOperations items;

    public BulkItemScope(ObjectProvider<PlatformTransactionManager> transactions) {
        PlatformTransactionManager manager = transactions.getIfUnique();
        if (manager == null) {
            this.items = TransactionOperations.withoutTransaction();
        } else {
            TransactionTemplate nested = new TransactionTemplate(manager);
            nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            nested.setName("bulk-item");
            this.items = nested;
        }
    }

    TransactionOperations items() {
        return items;
    }
}
