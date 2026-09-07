package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.repository.SearchDeliveryRepository;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository;
import com.greenwhite.dwh.instance.search.repository.SearchProjectionReader;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** All network I/O runs after the short repository transactions have returned. */
@Component
public class SearchDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(SearchDeliveryWorker.class);
    private final TypesenseClient client;
    private final SearchProjectionReader reader;
    private final SearchDeliveryRepository delivery;
    private final SearchIndexStateRepository state;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private UUID owner;

    @Autowired
    public SearchDeliveryWorker(TypesenseClient client, SearchProjectionReader reader,
            SearchDeliveryRepository delivery, SearchIndexStateRepository state) {
        this(client, reader, delivery, state, Clock.systemUTC(), () -> ThreadLocalRandom.current().nextDouble());
    }

    public SearchDeliveryWorker(TypesenseClient client, SearchProjectionReader reader,
            SearchDeliveryRepository delivery, SearchIndexStateRepository state, Clock clock, DoubleSupplier jitter) {
        this.client = client;
        this.reader = reader;
        this.delivery = delivery;
        this.state = state;
        this.clock = clock;
        this.jitter = jitter;
    }

    public synchronized void startLifecycle(UUID owner) {
        if (this.owner != null) throw new IllegalStateException("Worker lifecycle already started");
        state.recoverOwnership(owner);
        this.owner = owner;
    }

    public synchronized void runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Search delivery cannot run in a business transaction");
        }
        if (owner == null || !client.isEnabled()) return;
        delivery.releaseUnfinishedCycle(owner);
        var generation = state.deliveryGeneration(owner);
        try {
            if (generation.isEmpty()) {
                boolean tasksExist = client.collectionExists(TypesenseClient.COL_TASKS);
                boolean projectsExist = client.collectionExists(TypesenseClient.COL_PROJECTS);
                boolean usersExist = client.collectionExists(TypesenseClient.COL_USERS);
                state.registerInitial(tasksExist && projectsExist && usersExist, owner);
                generation = state.deliveryGeneration(owner);
            }
            if (generation.isEmpty()) return;
            var initial = generation.get();
            if (initial.state().equals("BUILDING") && initial.discoveryEntity().equals("TASK") && initial.discoveryAfterId() == 0) {
                for (String type : java.util.List.of("TASK", "PROJECT", "USER")) {
                    client.ensureCollection(initial.collections().get(type), type);
                }
            }
            state.discoverPage(initial, owner, 100);
        } catch (TypesenseException unavailable) {
            log.warn("Search background initialization will retry after dependency failure");
            return;
        }
        var target = generation.get();
        var claims = delivery.claim(target.id(), owner, clock.instant(), 100);
        try {
            for (var claim : claims) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    var projection = reader.read(claim.entityType(), claim.entityId());
                    if (projection.isEmpty() || projection.get().revision() != claim.revision()) continue;
                    var value = projection.get();
                    String collection = target.collections().get(claim.entityType());
                    if (value.document() == null) client.deleteDocument(collection, Long.toString(value.entityId()));
                    else client.upsertDocument(collection, value.document());
                    delivery.acknowledge(claim, value.fingerprint());
                } catch (RuntimeException failure) {
                    // Persist only a fixed code. Source content and downstream error bodies never become logs.
                    delivery.failed(claim, clock.instant().plus(retryDelay(claim.attempts() + 1)));
                }
            }
        } finally {
            for (var claim : claims) delivery.release(claim);
        }
        if (target.state().equals("BUILDING")) state.activateInitial(target.id(), target.version(), owner);
    }

    private Duration retryDelay(int attempt) {
        long seconds = Math.min(300, 1L << Math.min(30, Math.max(0, attempt - 1)));
        double bounded = Math.max(0, Math.min(1, jitter.getAsDouble()));
        return Duration.ofMillis(Math.min(300_000, Math.round(seconds * 1000 * (0.9 + bounded * 0.2))));
    }
}
