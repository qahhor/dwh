package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository.FallbackSearch;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final SearchEngineQuery engine;
    private final SearchFallbackRepository fallbackRepository;
    private final SearchAccessPolicy accessPolicy;
    private final SearchResultBudget resultBudget;
    private final Supplier<SearchExecutionSnapshot> executionSnapshot;
    private final Supplier<SettingsSnapshot> fallbackPolicy;
    private final QueryLanguageConverter queryConverter;
    private SearchMetrics metrics = SearchMetrics.unmetered();

    @Autowired
    public SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchPolicyProvider policyProvider,
            SearchExecutionSnapshotReader snapshotReader,
            Optional<QueryLanguageConverter> queryConverter,
            Optional<SearchMetrics> metrics) {
        this(
                typesense,
                fallbackRepository,
                accessPolicy,
                resultBudget,
                policyProvider,
                snapshotReader,
                queryConverter.orElseGet(QueryLanguageConverter::new));
        this.metrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchPolicyProvider policyProvider,
            SearchExecutionSnapshotReader snapshotReader,
            Optional<SearchMetrics> metrics) {
        this(
                typesense,
                fallbackRepository,
                accessPolicy,
                resultBudget,
                policyProvider,
                snapshotReader,
                new QueryLanguageConverter());
        this.metrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchPolicyProvider policyProvider,
            SearchExecutionSnapshotReader snapshotReader) {
        this(
                typesense,
                fallbackRepository,
                accessPolicy,
                resultBudget,
                snapshotReader::read,
                policyProvider::snapshot,
                new QueryLanguageConverter());
    }

    public SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchPolicyProvider policyProvider,
            SearchExecutionSnapshotReader snapshotReader,
            QueryLanguageConverter queryConverter) {
        this(
                typesense,
                fallbackRepository,
                accessPolicy,
                resultBudget,
                snapshotReader::read,
                policyProvider::snapshot,
                queryConverter);
    }

    /** Policy/snapshot boundary shared with the later saved-settings provider. */
    protected SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchQueryPolicy queryPolicy,
            Supplier<IndexSnapshot> indexSnapshot) {
        this(
                typesense,
                fallbackRepository,
                accessPolicy,
                resultBudget,
                () -> new SearchExecutionSnapshot(indexSnapshot.get(), new SettingsSnapshot(1, queryPolicy)),
                () -> new SettingsSnapshot(1, queryPolicy),
                new QueryLanguageConverter());
    }

    private SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            Supplier<SearchExecutionSnapshot> executionSnapshot,
            Supplier<SettingsSnapshot> fallbackPolicy,
            QueryLanguageConverter queryConverter) {
        this.engine = new SearchEngineQuery(typesense, fallbackRepository);
        this.fallbackRepository = fallbackRepository;
        this.accessPolicy = accessPolicy;
        this.resultBudget = resultBudget;
        this.executionSnapshot = executionSnapshot;
        this.fallbackPolicy = fallbackPolicy;
        this.queryConverter = queryConverter != null ? queryConverter : new QueryLanguageConverter();
    }

    public SearchResult search(String query, String entityType, int limit) {
        return search(query, entityType, Integer.valueOf(limit));
    }

    public SearchResult search(String query, String entityType, Integer limit) {
        long started = System.nanoTime();
        SearchResult result = null;
        try {
            accessPolicy.requireSearchAccess();
            String cleanQuery = SearchRequestRules.normalizeQuery(query);
            String cleanEntityType = SearchRequestRules.normalizeEntityType(entityType);
            SearchRequestRules.validateLimit(limit);
            SearchExecutionSnapshot snapshot = readSnapshot();
            result = execute(
                    cleanQuery,
                    cleanEntityType,
                    limit,
                    snapshot.index(),
                    snapshot.settings().policy());
            return result;
        } finally {
            metrics.query(
                    entityType == null ? "ALL" : entityType.toUpperCase(Locale.ROOT),
                    result == null ? null : result.source(),
                    result == null,
                    result != null && result.degraded(),
                    System.nanoTime() - started);
        }
    }

    public PreviewResult preview(PreviewRequest request) {
        long started = System.nanoTime();
        SearchResult result = null;
        try {
            accessPolicy.requireSearchAccess();
            if (request.policy() != null) accessPolicy.requireSettingsRead();
            String query = SearchRequestRules.normalizeQuery(request.q());
            String entity = SearchRequestRules.normalizeEntityType(request.entity());
            SearchExecutionSnapshot snapshot = readSnapshot();
            SearchQueryPolicy policy =
                    request.policy() == null ? snapshot.settings().policy() : request.policy();
            result = execute(query, entity, null, snapshot.index(), policy);
            return new PreviewResult(result, snapshot.index().schemaProfile());
        } finally {
            metrics.query(
                    request == null || request.entity() == null
                            ? "ALL"
                            : request.entity().toUpperCase(Locale.ROOT),
                    result == null ? null : result.source(),
                    result == null,
                    result != null && result.degraded(),
                    System.nanoTime() - started);
        }
    }

    private SearchExecutionSnapshot readSnapshot() {
        try {
            return executionSnapshot.get();
        } catch (DataAccessException unavailable) {
            log.warn("search_snapshot_unavailable error={}", unavailable.toString());
            return new SearchExecutionSnapshot(
                    new IndexSnapshot(null, 0, Map.of(), null, false, false), fallbackPolicy.get());
        }
    }

    private SearchResult execute(
            String cleanQuery,
            String cleanEntityType,
            Integer limit,
            IndexSnapshot snapshot,
            SearchQueryPolicy currentPolicy) {
        int effectiveLimit =
                limit == null ? currentPolicy.globalLimit() : SearchRequestRules.effectiveLimit(limit, currentPolicy);

        Long exactId = SearchRequestRules.exactId(cleanQuery);
        if (exactId != null) {
            try {
                return fallbackResult(
                        cleanQuery,
                        fallbackRepository.searchExact(exactId, cleanEntityType),
                        effectiveLimit,
                        false,
                        true,
                        null);
            } catch (Exception exactFailure) {
                throw unavailable();
            }
        }

        QueryLanguageConverter.QueryExpansion expansion = queryConverter.expand(cleanQuery);
        List<String> queryVariants = expansion.variants();
        String suggestedQuery = expansion.suggestedCorrection();

        if (engine.enabled()) {
            try {
                List<CollectionSearch> groups = engine.groups(
                        cleanQuery, cleanEntityType, effectiveLimit, snapshot, currentPolicy, queryVariants);
                groups.forEach(group -> metrics.engine(group.entityType(), group.searchTimeMs()));
                List<SearchHit> hits = resultBudget.allocate(groups, effectiveLimit);
                long found = SearchEngineQuery.sumFound(groups);
                return new SearchResult(
                        cleanQuery, hits.size(), hits, found, found > hits.size(), "TYPESENSE", false, suggestedQuery);
            } catch (TypesenseException | DataAccessException unavailableOrInvalid) {
                log.warn("Typesense search failed; using PostgreSQL fallback");
            }
        }

        try {
            var searchResult = fallbackRepository.search(cleanQuery, queryVariants, cleanEntityType, effectiveLimit);
            if (searchResult == null) {
                searchResult = fallbackRepository.search(cleanQuery, cleanEntityType, effectiveLimit);
            }
            return fallbackResult(cleanQuery, searchResult, effectiveLimit, true, false, suggestedQuery);
        } catch (Exception fallbackFailure) {
            throw unavailable();
        }
    }

    private SearchResult fallbackResult(
            String query,
            FallbackSearch fallback,
            int effectiveLimit,
            boolean degraded,
            boolean countKnown,
            String suggestedQuery) {
        List<CollectionSearch> groups = fallback.groups().stream()
                .map(group -> new CollectionSearch(
                        group.entityType(),
                        group.hits().stream()
                                .map(hit -> new SearchHit(
                                        hit.entityType(), hit.id(), hit.title(), hit.description(), hit.targetUrl()))
                                .toList(),
                        group.hits().size(),
                        0))
                .toList();
        List<SearchHit> hits = resultBudget.allocate(groups, effectiveLimit);
        int available = groups.stream().mapToInt(group -> group.hits().size()).sum();
        boolean hasMore = fallback.groups().stream().anyMatch(group -> group.hasMore()) || available > hits.size();
        Long foundHits = countKnown ? (long) available : null;
        return new SearchResult(query, hits.size(), hits, foundHits, hasMore, "POSTGRES", degraded, suggestedQuery);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "error.search.unavailable");
    }

    public record SearchHit(String entityType, String id, String title, String description, String targetUrl) {}

    public record SearchResult(
            String query,
            int totalHits,
            List<SearchHit> hits,
            Long foundHits,
            boolean hasMore,
            String source,
            boolean degraded,
            String suggestedQuery) {
        public SearchResult {
            hits = List.copyOf(hits);
        }

        public SearchResult(
                String query,
                int totalHits,
                List<SearchHit> hits,
                Long foundHits,
                boolean hasMore,
                String source,
                boolean degraded) {
            this(query, totalHits, hits, foundHits, hasMore, source, degraded, null);
        }
    }
}
