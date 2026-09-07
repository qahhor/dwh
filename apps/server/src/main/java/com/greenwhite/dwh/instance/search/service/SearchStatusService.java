package com.greenwhite.dwh.instance.search.service;

import org.springframework.stereotype.Service;
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
    public SearchStatusService(SearchAccessPolicy access, SearchIndexStateRepository repository,
                               SearchPolicyProvider policies, TypesenseClient client) {
        this.access=access; this.repository=repository; this.policies=policies; this.client=client;
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
        boolean mismatch = index.legacy();
        Instant reconciled = null;
        for (var generation : observations) {
            Long documents = dependency.healthy() ? 0L : null;
            Boolean schemaMatches = dependency.healthy() ? Boolean.TRUE : null;
            String errorCode = dependency.healthy() ? null : "DEPENDENCY_UNAVAILABLE";
            for (var entry : generation.collections().entrySet()) {
                if (!dependency.healthy()) break;
                var collection = client.observeCollection(entry.getValue(),entry.getKey(),generation.schemaProfile());
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
            generations.add(new GenerationStatus(generation.id(),generation.state(),generation.active(),generation.schemaProfile(),
                    documents,null,schemaMatches,errorCode,generation.pending(),generation.failed(),lag,generation.createdAt()));
        }
        Boolean rebuild = policy==null ? null : !index.initialized() || mismatch || !policy.schemaProfile().equals(index.schemaProfile());
        var transport=client.transportBudgets();
        return new Status(dependency,index.initialized(),index.schemaProfile(),policy==null ? null : policy.schemaProfile(),
                rebuild,policies.degraded(),reconciled,List.copyOf(generations),
                new Budgets(transport.connectTimeoutMs(),transport.readTimeoutMs(),2000,rate));
    }
    public record Status(DependencyMetadata dependency, boolean initialized, String activeProfile, String configuredProfile,
                         Boolean rebuildRequired, boolean settingsDegraded, Instant lastSuccessfulReconciliation,
                         List<GenerationStatus> generations, Budgets budgets) {}
    public record GenerationStatus(UUID id, String state, boolean active, String registeredProfile, Long documentCount,
                                   Long storageBytes, Boolean schemaMatches, String errorCode, long pendingDeliveries, long failedDeliveries,
                                   Long queueLagSeconds, Instant createdAt) {}
    public record Budgets(int connectTimeoutMs, int readTimeoutMs, int fallbackTimeoutMs,
                          SearchPolicyProvider.EffectiveBudgets searchRate) {}
}
