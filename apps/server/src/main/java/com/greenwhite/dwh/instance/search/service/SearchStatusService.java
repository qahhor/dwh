package com.greenwhite.dwh.instance.search.service;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.DependencyMetadata;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Service
public class SearchStatusService {
    private final SearchAccessPolicy access;
    private final SearchIndexStateRepository repository;
    private final SearchPolicyProvider policies;
    private final TypesenseClient client;
    private final com.greenwhite.dwh.instance.search.repository.SearchJobRepository jobs;
    public SearchStatusService(SearchAccessPolicy access, SearchIndexStateRepository repository,
                               SearchPolicyProvider policies, TypesenseClient client,
                               com.greenwhite.dwh.instance.search.repository.SearchJobRepository jobs) {
        this.access=access; this.repository=repository; this.policies=policies; this.client=client;
        this.jobs=jobs;
    }
    public Status current() {
        access.requireSearchAccess();
        var index = repository.snapshot();
        var observations = repository.observations();
        SearchQueryPolicy policy = null;
        SearchPolicyProvider.EffectiveBudgets rate = null;
        try { policy=policies.snapshot().policy(); rate=policies.effectiveBudgets(policy); }
        catch (ApiException unavailable) { /* No valid settings have been observed yet. */ }
        var dependency = client.observeDependency();
        var generations = new ArrayList<GenerationStatus>();
        var rollbackTargets=new ArrayList<RollbackTarget>();
        boolean mismatch = index.legacy();
        Instant reconciled = null;
        for (var generation : observations) {
            Long documents = dependency.healthy() ? 0L : null;
            Long taskDocuments = null, projectDocuments = null, userDocuments = null;
            Boolean schemaMatches = dependency.healthy() ? Boolean.TRUE : null;
            String errorCode = dependency.healthy() ? null : "DEPENDENCY_UNAVAILABLE";
            for (var entry : generation.collections().entrySet()) {
                if (!dependency.healthy()) break;
                var collection = client.observeCollection(entry.getValue(),entry.getKey(),generation.schemaProfile());
                switch (entry.getKey()) {
                    case "TASK" -> taskDocuments=collection.documentCount();
                    case "PROJECT" -> projectDocuments=collection.documentCount();
                    case "USER" -> userDocuments=collection.documentCount();
                }
                if (errorCode == null || "COLLECTION_MISSING".equals(collection.errorCode())) errorCode=collection.errorCode();
                if (documents != null) {
                    try { documents=collection.documentCount() == null ? null : Math.addExact(documents,collection.documentCount()); }
                    catch (ArithmeticException overflow) { documents=null; }
                }
                if (Boolean.FALSE.equals(collection.schemaMatches())) schemaMatches=false;
                else if (collection.schemaMatches()==null && !Boolean.FALSE.equals(schemaMatches)) schemaMatches=null;
            }
            if (generation.active()) {
                reconciled=generation.verifiedAt();
                mismatch |= Boolean.FALSE.equals(schemaMatches);
            }
            Long lag = generation.oldestPending()==null ? 0L : Math.max(0,Duration.between(generation.oldestPending(),Instant.now()).getSeconds());
            if (generation.state().equals("RETAINED") && generation.schemaVersion()==1 && Boolean.TRUE.equals(schemaMatches))
                rollbackTargets.add(new RollbackTarget(generation.id(),generation.schemaProfile(),generation.verifiedAt()));
            generations.add(new GenerationStatus(generation.id(),generation.state(),generation.active(),generation.schemaProfile(),
                    documents,new EntityDocumentCounts(taskDocuments,projectDocuments,userDocuments),null,
                    schemaMatches,errorCode,generation.pending(),generation.failed(),lag,generation.createdAt()));
        }
        Boolean rebuild = policy==null ? null : !index.initialized() || mismatch || !policy.schemaProfile().equals(index.schemaProfile());
        var transport=client.transportBudgets();
        return new Status(dependency,index.initialized(),index.schemaProfile(),policy==null ? null : policy.schemaProfile(),
                rebuild,policies.degraded(),reconciled,List.copyOf(generations),
                new Budgets(transport.connectTimeoutMs(),transport.readTimeoutMs(),2000,rate),
                jobs.page(20,null,null),List.copyOf(rollbackTargets));
    }
    public record Status(DependencyMetadata dependency, boolean initialized, String activeProfile, String configuredProfile,
                         Boolean rebuildRequired, boolean settingsDegraded, Instant lastSuccessfulReconciliation,
                         List<GenerationStatus> generations, Budgets budgets,
                         List<com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.JobStatus> jobs,List<RollbackTarget> rollbackTargets) {}
    /** A retained supported schema is eligible for catch-up and fresh verification, not an already-authorized cutover. */
    public record RollbackTarget(UUID id,String schemaProfile,Instant lastVerifiedAt) {}
    public record GenerationStatus(UUID id, String state, boolean active, String registeredProfile, Long documentCount,
                                   EntityDocumentCounts entityDocumentCounts, Long storageBytes, Boolean schemaMatches,
                                   String errorCode, long pendingDeliveries, long failedDeliveries,
                                   Long queueLagSeconds, Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EntityDocumentCounts(@JsonProperty("TASK") Long task, @JsonProperty("PROJECT") Long project,
                                       @JsonProperty("USER") Long user) {}
    public record Budgets(int connectTimeoutMs, int readTimeoutMs, int fallbackTimeoutMs,
                          SearchPolicyProvider.EffectiveBudgets searchRate) {}
}
