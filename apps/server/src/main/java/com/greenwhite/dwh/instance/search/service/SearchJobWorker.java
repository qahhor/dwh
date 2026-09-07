package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.repository.*;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import java.util.List;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.JobStatus;
import com.greenwhite.dwh.instance.search.repository.SearchGenerationRepository.FrozenGeneration;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;

@Component
public class SearchJobWorker implements AutoCloseable {
    private final TypesenseClient client;
    private final SearchDeliveryWorker delivery;
    private final SearchIndexStateRepository state;
    private final SearchJobRepository jobs;
    private final SearchGenerationRepository generations;
    private final SearchGenerationService generationService;
    private final SearchJobService service;
    private final SearchReconciliationService reconciliation;
    private final SearchStoragePreflight storage;
    private UUID owner,proofJob;
    private SearchReconciliationService.Proof proof;
    private FrozenGeneration frozen;
    private long expectedVersion;
    private boolean closed;
    private SearchMetrics metrics=SearchMetrics.unmetered();
    @org.springframework.beans.factory.annotation.Autowired
    public SearchJobWorker(TypesenseClient client,SearchDeliveryWorker delivery,SearchIndexStateRepository state,
            SearchJobRepository jobs,SearchGenerationRepository generations,SearchGenerationService generationService,
            SearchJobService service,SearchReconciliationService reconciliation,SearchStoragePreflight storage,
            java.util.Optional<SearchMetrics> metrics) {
        this(client,delivery,state,jobs,generations,generationService,service,reconciliation,storage);
        this.metrics=metrics.orElseGet(SearchMetrics::unmetered);
    }
    public SearchJobWorker(TypesenseClient client,SearchDeliveryWorker delivery,SearchIndexStateRepository state,
            SearchJobRepository jobs,SearchGenerationRepository generations,SearchGenerationService generationService,
            SearchJobService service,SearchReconciliationService reconciliation,SearchStoragePreflight storage) {
        this.client=client;this.delivery=delivery;this.state=state;this.jobs=jobs;this.generations=generations;
        this.generationService=generationService;this.service=service;this.reconciliation=reconciliation;this.storage=storage;
    }
    public synchronized void startLifecycle(UUID owner) {
        if (this.owner!=null) throw new IllegalStateException("Worker lifecycle already started");
        this.owner=owner;
    }
    public synchronized void runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalTransactionStateException("Search jobs cannot run in a business transaction");
        if (closed || owner==null || !client.isEnabled() || Thread.currentThread().isInterrupted()) return;
        if (!state.owns(owner)) { discardProof();return; }
        if (proofJob!=null && jobs.find(proofJob).map(job -> !List.of("VERIFYING","ACTIVATING").contains(job.state())).orElse(true)) discardProof();
        if (!state.snapshot().initialized() && !jobs.anyJobExists()) {
            boolean legacy=client.collectionExists(TypesenseClient.COL_TASKS) && client.collectionExists(TypesenseClient.COL_PROJECTS)
                    && client.collectionExists(TypesenseClient.COL_USERS);
            service.initialize(owner,legacy);
        }
        var claimed=jobs.claim(owner);
        if (claimed.isEmpty()) { discardProof();return; }
        JobStatus job=claimed.get();
        try {
            if (proofJob!=null && !proofJob.equals(job.id())) discardProof();
            var generation=generations.find(job.generationId()).orElseThrow();
            boolean check=job.action().equals("CHECK");
            if (!check && generation.schemaVersion()!=1) { service.failOwned(job,owner,"GENERATION_REQUIRES_REBUILD");return; }
            if (job.action().equals("ROLLBACK") && !generation.state().equals("RETAINED")) {
                service.failOwned(job,owner,"GENERATION_NOT_RETAINED");return;
            }
            if (job.state().equals("RUNNING")) {
                if (!check) {
                    storage.requireSpace();
                    for (String type:List.of("TASK","PROJECT","USER"))
                        client.ensureCollection(generation.collections().get(type),type,generation.schemaProfile());
                    if (!generation.discoveryEntity().equals("DONE")) {
                        state.discoverPage(generation.delivery(state.snapshot().version()),owner,100);
                        jobs.checkpoint(job.id(),owner,"RUNNING",generations.processed(generation.id()),0,null);return;
                    }
                    delivery.runGeneration(generation.delivery(state.snapshot().version()));
                    if (generations.exhausted(generation.id())>0) { service.failOwned(job,owner,"DELIVERY_RETRIES_EXHAUSTED");return; }
                    if (generations.pending(generation.id())>0) {
                        jobs.checkpoint(job.id(),owner,"RUNNING",generations.processed(generation.id()),0,null);return;
                    }
                }
                frozen=generation;expectedVersion=state.snapshot().version();
                proof=reconciliation.begin(generation.delivery(expectedVersion));proofJob=job.id();
                if (!jobs.checkpoint(job.id(),owner,"VERIFYING",generations.processed(generation.id()),0,null)) discardProof();
                return;
            }
            if (proof==null) { jobs.checkpoint(job.id(),owner,"RUNNING",job.processedCount(),0,null);return; }
            if (job.state().equals("ACTIVATING")) {
                if (!generationService.finish(proof,frozen,job,owner,expectedVersion)) jobs.checkpoint(job.id(),owner,"RUNNING",job.processedCount(),0,null);
                discardProof();return;
            }
            boolean complete=proof.advance();
            if (!jobs.checkpoint(job.id(),owner,"VERIFYING",Math.max(job.processedCount(),proof.processed()),0,complete ? proof.summary() : null)) { discardProof();return; }
            if (!complete) return;
            if (check) {
                if (!generationService.finish(proof,frozen,job,owner,expectedVersion)) jobs.checkpoint(job.id(),owner,"RUNNING",job.processedCount(),0,null);
                discardProof();return;
            }
            if (!proof.revisionsUnchanged() || proof.summary().pending()>0) {
                jobs.checkpoint(job.id(),owner,"RUNNING",job.processedCount(),0,proof.summary());discardProof();return;
            }
            if (!proof.summary().successful()) { service.failOwned(job,owner,"RECONCILIATION_MISMATCH");discardProof();return; }
            if (!jobs.checkpoint(job.id(),owner,"ACTIVATING",job.processedCount(),0,proof.summary())) discardProof();
        } catch (RuntimeException failure) {
            try {
                if (!Thread.currentThread().isInterrupted()) service.failOwned(job,owner,safeFailure(failure));
            } finally { discardProof(); }
        } finally {
            if (state.owns(owner)) jobs.find(job.id()).filter(updated -> !updated.state().equals(job.state())).ifPresent(updated -> {
                if (List.of("SUCCEEDED","FAILED","CANCELLED").contains(updated.state())) discardProof();
                metrics.job(updated.action(),updated.state(),updated.finishedAt()==null ? null : java.time.Duration.between(updated.createdAt(),updated.finishedAt()));
                if (updated.state().equals("SUCCEEDED") && !updated.action().equals("CHECK")) metrics.switched(updated.action());
            });
        }
    }
    private static String safeFailure(RuntimeException failure) {
        if (failure instanceof SearchProjectionReader.DocumentTooLargeException) return "DOCUMENT_TOO_LARGE";
        if (failure instanceof com.greenwhite.dwh.instance.common.error.ApiException && failure.getMessage()!=null
                && List.of("INSUFFICIENT_SEARCH_STORAGE","SEARCH_STORAGE_UNAVAILABLE").contains(failure.getMessage())) return failure.getMessage();
        return failure instanceof TypesenseException ? "SEARCH_DEPENDENCY_FAILED" : "SEARCH_JOB_FAILED";
    }
    private void discardProof() {
        if (proof!=null) proof.close();
        proof=null;proofJob=null;frozen=null;
    }
    @Override public synchronized void close() { closed=true;discardProof(); }
}
