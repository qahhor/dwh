package com.greenwhite.dwh.instance.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchDeliveryIntegrationTest extends SearchDeliveryTestSupport {
    @Test void nextCycleReleasesOnlyItsOwnClaimLeftAfterDatabaseCheckpointFailure() {
        var generation = activeGeneration();
        long id = user("Checkpoint retry");
        long otherId = user("Other owner");
        var otherOwner = java.util.UUID.randomUUID();
        jdbc.sql("""
                insert into search_generation_delivery(generation_id,entity_type,entity_id,attempted_revision,owner_token)
                values (:generation,'USER',:id,1,:owner)
                """).param("generation", generation).param("id", otherId).param("owner", otherOwner).update();
        var failedDatabase = new FailingCheckpointDataSource(database);
        var failedManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(failedDatabase);
        delivery = SearchRevisionIntegrationTest.proxied(new com.greenwhite.dwh.instance.search.repository.SearchDeliveryRepository(
                org.springframework.jdbc.core.simple.JdbcClient.create(failedDatabase)), failedManager);
        worker = new com.greenwhite.dwh.instance.search.service.SearchDeliveryWorker(client, reader, delivery, state, clock, () -> 0.5);
        worker.startLifecycle(owner);
        // Restore the foreign claim after lifecycle recovery; no next-cycle cleanup may release it.
        jdbc.sql("update search_generation_delivery set owner_token=:owner where entity_id=:id and entity_type='USER'")
                .param("owner", otherOwner).param("id", otherId).update();
        beforeWrite = exchange -> failedDatabase.fail.set(true);
        assertThatThrownBy(worker::runOnce).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(failedDatabase.rejected.get()).isEqualTo(3);
        assertThat(documents).containsKey("users/" + id);
        assertThat(delivered("USER", id)).isZero();
        failedDatabase.fail.set(false);
        beforeWrite = exchange -> {};
        worker.runOnce();
        assertThat(delivered("USER", id)).isOne();
        assertThat(documents.get("users/" + id)).containsEntry("name", "Checkpoint retry");
        assertThat(jdbc.sql("select worker_owner from search_index_state where id=1").query(java.util.UUID.class).single()).isEqualTo(owner);
        assertThat(jdbc.sql("select owner_token from search_generation_delivery where entity_type='USER' and entity_id=:id")
                .param("id", otherId).query(java.util.UUID.class).single()).isEqualTo(otherOwner);
    }

    private static final class FailingCheckpointDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {
        final javax.sql.DataSource delegate;
        final java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        FailingCheckpointDataSource(javax.sql.DataSource delegate) { this.delegate = delegate; }
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException { return wrap(delegate.getConnection()); }
        @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
            return wrap(delegate.getConnection(user,password));
        }
        private java.sql.Connection wrap(java.sql.Connection connection) {
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && fail.get()
                                && ((String) args[0]).stripLeading().startsWith("update search_generation_delivery")) {
                            rejected.incrementAndGet();
                            throw new java.sql.SQLException("Injected checkpoint outage", "08006");
                        }
                        try { return method.invoke(connection,args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
        }
    }

    @Test void oldOwnerAndOldAttemptCannotAcknowledgeTheReplacementClaim() {
        var generation = activeGeneration();
        long id = user("Owner fence");
        var oldClaim = delivery.claim(generation, owner, clock.instant(), 100).getFirst();
        var oldWorker = worker;
        recreateWorker();
        var current = delivery.claim(generation, owner, clock.instant(), 100).getFirst();
        assertThat(delivery.acknowledge(oldClaim, "old-fingerprint")).isFalse();
        oldWorker.runOnce();
        assertThat(delivered("USER", id)).isZero();
        assertThat(documents).isEmpty();
        delivery.release(current);
        users.updateUser(id, "Next revision", null, null, null, null, null, null, null, null, id);
        var next = delivery.claim(generation, owner, clock.instant(), 100).getFirst();
        assertThat(delivery.acknowledge(current, "old-attempt")).isFalse();
        assertThat(delivered("USER", id)).isZero();
        delivery.release(next);
        worker.runOnce();
        assertThat(delivered("USER", id)).isEqualTo(2);
    }

    @Test void deletionAfterInflightUpsertIsDeliveredAfterRestart() throws Exception {
        activeGeneration();
        long reporter = user("Reporter");
        worker.runOnce();
        long id = task(reporter, "Will be deleted");
        var entered = new CountDownLatch(1);
        var respond = new CountDownLatch(1);
        beforeWrite = exchange -> { entered.countDown(); SearchRevisionIntegrationTest.await(respond); };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(worker::runOnce);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                tx.executeWithoutResult(status -> {
                    jdbc.sql("delete from ms_task_members where task_id=:id").param("id", id).update();
                    jdbc.sql("delete from ms_tasks where id=:id").param("id", id).update();
                    publisher.changed("TASK", id);
                });
            } finally { respond.countDown(); }
            future.get(10, TimeUnit.SECONDS);
        }
        assertThat(documents).containsKey("tasks/" + id);
        assertThat(delivered("TASK", id)).isOne();
        beforeWrite = exchange -> {};
        recreateWorker();
        failures.set(1);
        worker.runOnce();
        assertThat(delivered("TASK", id)).isOne();
        clock.advance(Duration.ofSeconds(1));
        recreateWorker();
        worker.runOnce();
        assertThat(documents).doesNotContainKey("tasks/" + id);
        assertThat(delivered("TASK", id)).isEqualTo(2);
    }

    @Test void workerCannotSeeTaskCreatedInsideHeldBusinessTransaction() throws Exception {
        activeGeneration();
        long reporter = user("Reporter");
        worker.runOnce();
        var created = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var id = new java.util.concurrent.atomic.AtomicLong();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> tx.executeWithoutResult(status -> {
                id.set(task(reporter, "Committed only")); created.countDown(); SearchRevisionIntegrationTest.await(commit);
            }));
            try {
                assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
                worker.runOnce();
                assertThat(documents).doesNotContainKey("tasks/" + id.get());
                assertThat(delivered("TASK", id.get())).isZero();
            } finally { commit.countDown(); }
            future.get(10, TimeUnit.SECONDS);
        }
        worker.runOnce();
        assertThat(documents).containsKey("tasks/" + id.get());
        assertThat(delivered("TASK", id.get())).isOne();
    }

    @Test void acknowledgementOfInflightRevisionDoesNotConsumeNewerCommitAndHttpHoldsNoTransaction() throws Exception {
        activeGeneration();
        long id = user("Revision one");
        var entered = new CountDownLatch(1);
        var respond = new CountDownLatch(1);
        beforeWrite = exchange -> {
            assertThat(jdbc.sql("select count(*) from pg_stat_activity where datname=current_database() and state='idle in transaction'")
                    .query(Long.class).single()).isZero();
            entered.countDown(); SearchRevisionIntegrationTest.await(respond);
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(worker::runOnce);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                users.updateUser(id, "Revision two", null, null, null, null, null, null, null, null, id);
            } finally { respond.countDown(); }
            future.get(10, TimeUnit.SECONDS);
        }
        assertThat(delivered("USER", id)).isOne();
        assertThat(documents.get("users/" + id)).containsEntry("name", "Revision one");
        beforeWrite = exchange -> {};
        worker.runOnce();
        assertThat(delivered("USER", id)).isEqualTo(2);
        assertThat(documents.get("users/" + id)).containsEntry("name", "Revision two");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void blockedOrAnonymizedUserEndsAbsentAfterFailedQueuedUpsertAndRestart(boolean anonymize) {
        activeGeneration();
        long id = user("Will disappear");
        failures.set(1);
        worker.runOnce();
        assertThat(delivered("USER", id)).isZero();
        if (anonymize) users.anonymizeUser(id, id); else users.setUserState(id, "P", id);
        recreateWorker();
        worker.runOnce();
        assertThat(delivered("USER", id)).isEqualTo(2);
        assertThat(documents).doesNotContainKey("users/" + id);
        assertThat(writes).contains("DELETE /collections/users/documents/" + id);
        assertThat(jdbc.sql("select auth_version from md_users where id=:id").param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test void eighthFailureIsTerminalForThatRevisionAndNewRevisionResetsFailures() {
        activeGeneration();
        long id = user("Retry");
        failures.set(100);
        for (int attempt = 1; attempt <= 8; attempt++) {
            int previous = requests.get();
            worker.runOnce();
            assertThat(requests.get()).isEqualTo(previous + 1);
            assertThat(delivered("USER", id)).isZero();
            worker.runOnce();
            assertThat(requests.get()).isEqualTo(previous + 1);
            clock.advance(Duration.ofSeconds(1L << (attempt - 1)));
        }
        int exhausted = requests.get();
        clock.advance(Duration.ofDays(1));
        recreateWorker();
        worker.runOnce();
        assertThat(requests.get()).isEqualTo(exhausted);
        failures.set(0);
        users.updateUser(id, "Recovered", null, null, null, null, null, null, null, null, id);
        worker.runOnce();
        assertThat(delivered("USER", id)).isEqualTo(2);
        assertThat(documents.get("users/" + id)).containsEntry("name", "Recovered");
    }

    @Test void eachCycleDeliversAtMostOneHundredAndRestartResumesTheRemainder() {
        activeGeneration();
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 205; i++) user("Bounded " + i);
        });
        worker.runOnce();
        assertThat(documents).hasSize(100);
        recreateWorker();
        worker.runOnce();
        assertThat(documents).hasSize(200);
        worker.runOnce();
        assertThat(documents).hasSize(205);
    }
}
