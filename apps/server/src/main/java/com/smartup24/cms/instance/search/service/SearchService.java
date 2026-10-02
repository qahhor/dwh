package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.smartup24.cms.instance.search.service.SearchScopes.Caller;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * The global search (ADR-0032, 10.3; plan 10/10, item 5.8): the records of the entities with the SEARCH capability the
 * caller may see — their module on, their {@code view} right held — in the caller's scope. Typesense answers the
 * candidates, filtered by the scope keys of their documents, and each is checked again in the database with the
 * entity's own scope before it is answered; without Typesense the same scope predicate reads PostgreSQL.
 */
@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final SearchEngineQuery engine;
    private final SearchDatabaseQuery database;
    private final SearchAccessPolicy accessPolicy;
    private final SearchResultBudget resultBudget;
    private final Supplier<SearchExecutionSnapshot> executionSnapshot;
    private final Supplier<SettingsSnapshot> fallbackPolicy;
    private final QueryLanguageConverter queryConverter;
    private final SearchEntities entities;
    private final SearchScopes scopes;
    private final SearchFieldPolicies fieldPolicies;
    private final SearchMetrics metrics;

    @Autowired
    public SearchService(
            TypesenseSearch typesense,
            SearchFallbackRepository fallbackRepository,
            SearchAccessPolicy accessPolicy,
            SearchResultBudget resultBudget,
            SearchPolicyProvider policyProvider,
            SearchExecutionSnapshotReader snapshotReader,
            SearchEntities entities,
            SearchScopes scopes,
            SearchFieldPolicies fieldPolicies,
            Optional<QueryLanguageConverter> queryConverter,
            Optional<SearchMetrics> metrics) {
        this.database = new SearchDatabaseQuery(fallbackRepository, scopes);
        this.engine = new SearchEngineQuery(typesense, database, fallbackRepository, fieldPolicies, scopes);
        this.accessPolicy = accessPolicy;
        this.resultBudget = resultBudget;
        this.executionSnapshot = snapshotReader::read;
        this.fallbackPolicy = policyProvider::snapshot;
        this.queryConverter = queryConverter.orElseGet(QueryLanguageConverter::new);
        this.entities = entities;
        this.scopes = scopes;
        this.fieldPolicies = fieldPolicies;
        this.metrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public SearchResult search(String query, String entityType, int limit) {
        return search(query, entityType, Integer.valueOf(limit));
    }

    public SearchResult search(String query, String entityType, Integer limit) {
        long started = System.nanoTime();
        SearchResult result = null;
        String label = null;
        try {
            Caller caller = scopes.caller(accessPolicy.requireSearchAccess());
            String cleanQuery = SearchRequestRules.normalizeQuery(query);
            List<SearchEntity> targets = targets(entityType);
            label = label(entityType, targets);
            SearchRequestRules.validateLimit(limit);
            SearchExecutionSnapshot snapshot = readSnapshot();
            result = execute(
                    cleanQuery,
                    targets,
                    limit,
                    snapshot.index(),
                    snapshot.settings().policy(),
                    caller);
            return result;
        } finally {
            record(label, result, started);
        }
    }

    /** The administrator's preview of the settings: a search with a draft policy, in the administrator's own scope. */
    public PreviewResult preview(PreviewRequest request) {
        long started = System.nanoTime();
        SearchResult result = null;
        String label = null;
        try {
            Caller caller = scopes.caller(accessPolicy.requireAdministrator());
            if (request.policy() != null) {
                accessPolicy.requireSettingsRead();
                fieldPolicies.requireKnown(request.policy());
            }
            String query = SearchRequestRules.normalizeQuery(request.q());
            List<SearchEntity> targets = targets(request.entity());
            label = label(request.entity(), targets);
            SearchExecutionSnapshot snapshot = readSnapshot();
            SearchQueryPolicy policy =
                    request.policy() == null ? snapshot.settings().policy() : request.policy();
            result = execute(query, targets, null, snapshot.index(), policy, caller);
            return new PreviewResult(result, snapshot.index().schemaProfile());
        } finally {
            record(label, result, started);
        }
    }

    /** The entities the caller may search, for the categories of the search screen. */
    public List<SearchCategory> categories() {
        accessPolicy.requireSearchAccess();
        return entities.visible().stream()
                .map(entity -> {
                    var menu = entity.definition().menu();
                    return new SearchCategory(
                            entity.code(), menu == null ? null : menu.labelKey(), menu == null ? null : menu.icon());
                })
                .toList();
    }

    /** {@code ALL} — every entity the caller may search — or the one named; another name is a 400. */
    private List<SearchEntity> targets(@Nullable String entityType) {
        List<SearchEntity> visible = entities.visible();
        String wanted = SearchRequestRules.normalizeEntityType(entityType);
        if (wanted.equals(SearchRequestRules.ALL)) return visible;
        return visible.stream()
                .filter(entity -> entity.code().equals(wanted))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.category_unknown"));
    }

    private void record(@Nullable String label, @Nullable SearchResult result, long started) {
        metrics.query(
                label,
                result == null ? null : result.source(),
                result == null,
                result != null && result.degraded(),
                System.nanoTime() - started);
    }

    /** The entity label of the metrics: {@code ALL} or the code of the one entity searched, both finite. */
    private static String label(@Nullable String entityType, List<SearchEntity> targets) {
        return entityType == null || SearchRequestRules.ALL.equalsIgnoreCase(entityType.trim())
                ? SearchRequestRules.ALL
                : targets.getFirst().code();
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
            List<SearchEntity> targets,
            @Nullable Integer limit,
            IndexSnapshot snapshot,
            SearchQueryPolicy currentPolicy,
            Caller caller) {
        int effectiveLimit =
                limit == null ? currentPolicy.globalLimit() : SearchRequestRules.effectiveLimit(limit, currentPolicy);

        Long exactId = SearchRequestRules.exactId(cleanQuery);
        if (exactId != null) {
            try {
                return result(
                        cleanQuery,
                        database.exact(exactId, targets, caller),
                        effectiveLimit,
                        "POSTGRES",
                        false,
                        true,
                        null);
            } catch (RuntimeException exactFailure) {
                log.warn("search_exact_failed error={}", exactFailure.toString());
                throw unavailable();
            }
        }

        QueryLanguageConverter.QueryExpansion expansion = queryConverter.expand(cleanQuery);
        List<String> queryVariants = expansion.variants();
        String suggestedQuery = expansion.suggestedCorrection();

        if (engine.enabled() && snapshot.initialized()) {
            try {
                List<CollectionSearch> groups = engine.groups(
                        cleanQuery, queryVariants, targets, effectiveLimit, snapshot, currentPolicy, caller);
                groups.forEach(group -> metrics.engine(group.entityType(), group.searchTimeMs()));
                return result(cleanQuery, groups, effectiveLimit, "TYPESENSE", false, true, suggestedQuery);
            } catch (TypesenseException | DataAccessException unavailableOrInvalid) {
                log.warn("Typesense search failed; using PostgreSQL fallback");
            }
        }

        try {
            List<CollectionSearch> groups = database.groups(cleanQuery, queryVariants, targets, effectiveLimit, caller);
            return result(cleanQuery, groups, effectiveLimit, "POSTGRES", true, false, suggestedQuery);
        } catch (RuntimeException fallbackFailure) {
            log.warn("search_fallback_failed error={}", fallbackFailure.toString());
            throw unavailable();
        }
    }

    private SearchResult result(
            String query,
            List<CollectionSearch> groups,
            int effectiveLimit,
            String source,
            boolean degraded,
            boolean countKnown,
            @Nullable String suggestedQuery) {
        List<SearchHit> hits = resultBudget.allocate(groups, effectiveLimit);
        long found = SearchEngineQuery.sumFound(groups);
        boolean hasMore = found > hits.size();
        return new SearchResult(
                query, hits.size(), hits, countKnown ? found : null, hasMore, source, degraded, suggestedQuery);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "error.search.unavailable");
    }

    /**
     * One hit: the code of its entity, the record's id, its title and description, and where it leads in the web
     * application — the entity's general screen {@code /e/<code>/<id>} or its own screen.
     */
    public record SearchHit(String entityType, String id, String title, String description, String targetUrl) {}

    /** An entity the caller may search: its code, the label key and icon of its menu item (null without one). */
    public record SearchCategory(
            String code,
            @Nullable String labelKey,
            @Nullable String icon) {}

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
    }
}
