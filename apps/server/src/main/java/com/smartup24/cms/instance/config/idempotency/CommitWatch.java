package com.smartup24.cms.instance.config.idempotency;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells whether the current transaction committed. A commit call throws both when the commit fails and when a hook
 * that runs after a successful commit fails; only the second leaves the work committed. Every synchronization's
 * {@link #afterCompletion} runs, whatever another one threw, and receives the real outcome.
 */
final class CommitWatch implements TransactionSynchronization {

    private volatile boolean committed;

    private CommitWatch() {}

    /** A watch on the current transaction; without transaction synchronization it never reports a commit. */
    static CommitWatch register() {
        CommitWatch watch = new CommitWatch();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(watch);
        }
        return watch;
    }

    boolean committed() {
        return committed;
    }

    @Override
    public void afterCompletion(int status) {
        committed = status == STATUS_COMMITTED;
    }
}
