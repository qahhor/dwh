package com.smartup24.cms.instance.search.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.ObservedGeneration;
import com.smartup24.cms.instance.search.repository.SearchJobRepository;
import com.smartup24.cms.instance.search.typesense.TypesenseCollections;
import com.smartup24.cms.instance.search.typesense.TypesenseHealth;
import com.smartup24.cms.instance.search.typesense.TypesenseHealth.DependencyMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The state of the search index for its administrator: the engine, every generation with its documents per entity
 * (ADR-0032, 10.3), the queue and the jobs. A rebuild is required when the index was never built, a collection's schema
 * drifted, the configured profile differs from the built one, or the entities with the SEARCH capability are not the
 * ones the active generation has collections for.
 */
@Service
public class SearchStatusService {

    private static final Logger log = LoggerFactory.getLogger(SearchStatusService.class);
    private final SearchAccessPolicy access;
    private final SearchIndexStateRepository repository;
    private final SearchPolicyProvider policies;
    private final TypesenseHealth health;
    private final TypesenseCollections collections;
    private final SearchJobRepository jobs;
    private final SearchEntities entities;

    public SearchStatusService(
            SearchAccessPolicy access,
            SearchIndexStateRepository repository,
            SearchPolicyProvider policies,
            TypesenseHealth health,
            TypesenseCollections collections,
            SearchJobRepository jobs,
            SearchEntities entities) {
        this.access = access;
        this.repository = repository;
        this.policies = policies;
        this.health = health;
        this.collections = collections;
        this.jobs = jobs;
        this.entities = entities;
    }

    public Status current() {
        access.requireAdministrator();
        var index = repository.snapshot();
        var observations = repository.observations();
        SearchQueryPolicy policy = null;
        SearchPolicyProvider.EffectiveBudgets rate = null;
        try {
            policy = policies.snapshot().policy();
            rate = policies.effectiveBudgets(policy);
        } catch (ApiException unavailable) {
            /* No valid settings have been observed yet. */
            log.debug("search_status_without_policy code={}", unavailable.getErrorCode());
        }
        var dependency = health.observeDependency();
        var generations = new ArrayList<GenerationStatus>();
        var rollbackTargets = new ArrayList<RollbackTarget>();
        boolean mismatch = index.legacy()
                || (index.initialized()
                        && !new HashSet<>(entities.codes())
                                .equals(index.collections().keySet()));
        Instant reconciled = null;
        for (var generation : observations) {
            var status = generationStatus(generation, dependency.healthy());
            if (generation.active()) {
                reconciled = generation.verifiedAt();
                mismatch |= Boolean.FALSE.equals(status.schemaMatches());
            }
            if (generation.state().equals("RETAINED")
                    && generation.schemaVersion() == 1
                    && Boolean.TRUE.equals(status.schemaMatches()))
                rollbackTargets.add(
                        new RollbackTarget(generation.id(), generation.schemaProfile(), generation.verifiedAt()));
            generations.add(status);
        }
        Boolean rebuild = policy == null
                ? null
                : !index.initialized() || mismatch || !policy.schemaProfile().equals(index.schemaProfile());
        var transport = health.transportBudgets();
        return new Status(
                dependency,
                index.initialized(),
                index.schemaProfile(),
                policy == null ? null : policy.schemaProfile(),
                rebuild,
                policies.degraded(),
                reconciled,
                List.copyOf(generations),
                new Budgets(transport.connectTimeoutMs(), transport.readTimeoutMs(), 2000, rate),
                jobs.page(20, null, null),
                List.copyOf(rollbackTargets));
    }

    /** Observes every collection of one generation; without a healthy dependency nothing is asked. */
    private GenerationStatus generationStatus(ObservedGeneration generation, boolean healthy) {
        Long documents = healthy ? 0L : null;
        Map<String, Long> perEntity = new LinkedHashMap<>();
        Boolean schemaMatches = healthy ? Boolean.TRUE : null;
        String errorCode = healthy ? null : "DEPENDENCY_UNAVAILABLE";
        for (var entry : generation.collections().entrySet()) {
            perEntity.put(entry.getKey(), null);
            if (!healthy) continue;
            var entity = entities.find(entry.getKey());
            if (entity.isEmpty()) {
                // A collection of an entity that no longer declares the search.
                schemaMatches = false;
                continue;
            }
            var collection = collections.observeCollection(entry.getValue(), entity.get(), generation.schemaProfile());
            perEntity.put(entry.getKey(), collection.documentCount());
            if (errorCode == null || "COLLECTION_MISSING".equals(collection.errorCode()))
                errorCode = collection.errorCode();
            documents = add(documents, collection.documentCount());
            if (Boolean.FALSE.equals(collection.schemaMatches())) schemaMatches = false;
            else if (collection.schemaMatches() == null && !Boolean.FALSE.equals(schemaMatches)) schemaMatches = null;
        }
        Long lag = generation.oldestPending() == null
                ? 0L
                : Math.max(
                        0,
                        Duration.between(generation.oldestPending(), Instant.now())
                                .getSeconds());
        return new GenerationStatus(
                generation.id(),
                generation.state(),
                generation.active(),
                generation.schemaProfile(),
                documents,
                perEntity,
                null,
                schemaMatches,
                errorCode,
                generation.pending(),
                generation.failed(),
                lag,
                generation.createdAt());
    }

    private static Long add(Long total, Long count) {
        if (total == null || count == null) return null;
        try {
            return Math.addExact(total, count);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    public record Status(
            DependencyMetadata dependency,
            boolean initialized,
            String activeProfile,
            String configuredProfile,
            Boolean rebuildRequired,
            boolean settingsDegraded,
            Instant lastSuccessfulReconciliation,
            List<GenerationStatus> generations,
            Budgets budgets,
            List<SearchManagementDtos.JobStatus> jobs,
            List<RollbackTarget> rollbackTargets) {}
    /** A retained supported schema is eligible for catch-up and fresh verification, not an already-authorized cutover. */
    public record RollbackTarget(UUID id, String schemaProfile, Instant lastVerifiedAt) {}

    /**
     * One generation as its administrator sees it.
     *
     * @param entityDocumentCounts the documents of each entity's collection by entity code, null when not observed
     */
    public record GenerationStatus(
            UUID id,
            String state,
            boolean active,
            String registeredProfile,
            Long documentCount,

            @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
            Map<String, Long> entityDocumentCounts,

            Long storageBytes,
            Boolean schemaMatches,
            String errorCode,
            long pendingDeliveries,
            long failedDeliveries,
            Long queueLagSeconds,
            Instant createdAt) {}

    public record Budgets(
            int connectTimeoutMs,
            int readTimeoutMs,
            int fallbackTimeoutMs,
            SearchPolicyProvider.EffectiveBudgets searchRate) {}
}
