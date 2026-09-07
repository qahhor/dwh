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
import java.util.*;
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
    private SearchMetrics metrics=SearchMetrics.unmetered();

    @Autowired
    public SearchDeliveryWorker(TypesenseClient client,SearchProjectionReader reader,SearchDeliveryRepository delivery,
            SearchIndexStateRepository state,Optional<SearchMetrics> metrics) {
        this(client,reader,delivery,state);this.metrics=metrics.orElseGet(SearchMetrics::unmetered);
    }

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

    public SearchDeliveryWorker(TypesenseClient client,SearchProjectionReader reader,SearchDeliveryRepository delivery,
            SearchIndexStateRepository state,Clock clock,DoubleSupplier jitter,SearchMetrics metrics) {
        this(client,reader,delivery,state,clock,jitter);this.metrics=metrics;
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
        if (generation.isEmpty()) return;
        state.discoverPage(generation.get(), owner, 100);
        runGeneration(generation.get());
    }

    /** Candidate delivery uses the same bounded claims and writer as active delivery. */
    public synchronized void runGeneration(SearchIndexStateRepository.Generation target) {
        if (owner==null || Thread.currentThread().isInterrupted()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalTransactionStateException("Search delivery cannot run in a business transaction");
        var claims = delivery.claim(target.id(), owner, clock.instant(), 100);
        try {
            Map<String,List<PendingDocument>> batches=new LinkedHashMap<>();
            for (var claim : claims) {
                if (Thread.currentThread().isInterrupted()) break;
                if (claim.attempts()>0) metrics.retry();
                try {
                    var projection = reader.read(claim.entityType(), claim.entityId());
                    if (projection.isEmpty() || projection.get().revision() != claim.revision()) continue;
                    var value = projection.get();
                    String collection = target.collections().get(claim.entityType());
                    if (value.document() == null) {
                        client.deleteDocument(collection, Long.toString(value.entityId()));
                        delivery.acknowledge(claim, value.fingerprint());
                    } else batches.computeIfAbsent(collection,key -> new ArrayList<>()).add(new PendingDocument(claim,value));
                } catch (RuntimeException failure) {
                    // Persist only a fixed code. Source content and downstream error bodies never become logs.
                    delivery.failed(claim, clock.instant().plus(retryDelay(claim.attempts() + 1)),
                            failure instanceof SearchProjectionReader.DocumentTooLargeException ? "DOCUMENT_TOO_LARGE" : "DELIVERY_FAILED");
                }
            }
            for (var batch:batches.entrySet()) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    var acks=client.importDocuments(batch.getKey(),batch.getValue().stream().map(value -> value.projection().document()).toList());
                    for (int i=0;i<batch.getValue().size();i++) {
                        var value=batch.getValue().get(i);
                        if (acks.get(i).success()) delivery.acknowledge(value.claim(),value.projection().fingerprint());
                        else delivery.failed(value.claim(),clock.instant().plus(retryDelay(value.claim().attempts()+1)),acks.get(i).errorCode());
                    }
                } catch (RuntimeException failure) {
                    for (var value:batch.getValue()) delivery.failed(value.claim(),clock.instant().plus(retryDelay(value.claim().attempts()+1)));
                }
            }
        } finally {
            for (var claim : claims) delivery.release(claim);
            var observation=delivery.observation(target.id());
            metrics.queue(target.id().equals(state.snapshot().generationId()),observation.pending(),observation.lagSeconds());
        }
    }

    private record PendingDocument(SearchDeliveryRepository.Claim claim,SearchProjectionReader.Projection projection) {}

    private Duration retryDelay(int attempt) {
        long seconds = Math.min(300, 1L << Math.min(30, Math.max(0, attempt - 1)));
        double bounded = Math.max(0, Math.min(1, jitter.getAsDouble()));
        return Duration.ofMillis(Math.min(300_000, Math.round(seconds * 1000 * (0.9 + bounded * 0.2))));
    }
}
