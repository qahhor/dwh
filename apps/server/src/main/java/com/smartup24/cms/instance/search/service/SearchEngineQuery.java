package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.smartup24.cms.instance.search.service.SearchScopes.Caller;
import com.smartup24.cms.instance.search.service.SearchScopes.IndexFilter;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionQuery;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Typesense path of {@link SearchService} (ADR-0032, 10.3): one query per entity the caller searches that has a
 * collection in the active generation, filtered by the caller's scope keys, then the language variants; every hit is
 * checked again in the database with the caller's scope before it is answered, so a stale index shows nothing the
 * caller may not see. An entity without a collection yet (its search declared after the last rebuild) is searched in
 * PostgreSQL. Failures surface as {@link TypesenseException} or a data access exception so the caller can fall back.
 */
final class SearchEngineQuery {

    // The category stays the service's so existing log routing keeps matching.
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** How many candidates per wanted hit the index is asked for: the database check may drop some. */
    private static final int OVERFETCH = 2;

    private final TypesenseSearch typesense;
    private final SearchDatabaseQuery database;
    private final SearchFallbackRepository repository;
    private final SearchFieldPolicies fieldPolicies;
    private final SearchScopes scopes;

    SearchEngineQuery(
            TypesenseSearch typesense,
            SearchDatabaseQuery database,
            SearchFallbackRepository repository,
            SearchFieldPolicies fieldPolicies,
            SearchScopes scopes) {
        this.typesense = typesense;
        this.database = database;
        this.repository = repository;
        this.fieldPolicies = fieldPolicies;
        this.scopes = scopes;
    }

    boolean enabled() {
        return typesense.isEnabled();
    }

    List<CollectionSearch> groups(
            String cleanQuery,
            List<String> queryVariants,
            List<SearchEntity> targets,
            int effectiveLimit,
            IndexSnapshot snapshot,
            SearchQueryPolicy policy,
            Caller caller) {
        if (!snapshot.initialized()) throw TypesenseException.uninitialized();
        List<CollectionQuery> queries = new ArrayList<>();
        List<SearchEntity> unindexed = new ArrayList<>();
        for (SearchEntity entity : targets) {
            String collection = snapshot.collections().get(entity.code());
            if (collection == null || collection.isBlank()) {
                unindexed.add(entity);
                continue;
            }
            IndexFilter filter = scopes.indexFilter(entity, caller);
            if (filter.nothing()) continue;
            queries.add(new CollectionQuery(
                    entity,
                    collection,
                    fieldPolicies.of(policy, entity),
                    filter.filterBy(),
                    effectiveLimit * OVERFETCH));
        }
        List<CollectionSearch> groups = typesense.multiSearch(cleanQuery, queries);
        int initialHits = groups.stream().mapToInt(g -> g.hits().size()).sum();
        if (initialHits < effectiveLimit && queryVariants.size() > 1) {
            for (int i = 1; i < queryVariants.size(); i++) {
                try {
                    groups = mergeGroups(groups, typesense.multiSearch(queryVariants.get(i), queries));
                } catch (RuntimeException variantFailed) {
                    // The main query answered; a failed spelling variant only narrows the hits, but it is a failure.
                    log.warn("search_variant_failed variant_index={}", i, variantFailed);
                }
            }
        }
        List<CollectionSearch> checked = new ArrayList<>(recheck(groups, queries, effectiveLimit, caller));
        if (!unindexed.isEmpty()) {
            try {
                checked.addAll(database.groups(cleanQuery, queryVariants, unindexed, effectiveLimit, caller));
            } catch (RuntimeException fallbackFailed) {
                // The Typesense groups still answer; the records found by the database are missing from this answer
                log.warn("search_unindexed_fallback_failed", fallbackFailed);
            }
        }
        return checked;
    }

    /**
     * Keeps the hits the database still finds in the caller's scope, at most {@code limit} per entity; the count found
     * drops by the candidates the check refused.
     */
    private List<CollectionSearch> recheck(
            List<CollectionSearch> groups, List<CollectionQuery> queries, int limit, Caller caller) {
        Map<String, SearchEntity> byCode = queries.stream()
                .collect(Collectors.toMap(query -> query.entity().code(), CollectionQuery::entity, (a, b) -> a));
        List<CollectionSearch> checked = new ArrayList<>(groups.size());
        for (CollectionSearch group : groups) {
            SearchEntity entity = byCode.get(group.entityType());
            if (entity == null) throw TypesenseException.invalidResponse();
            List<Long> ids =
                    group.hits().stream().map(hit -> Long.parseLong(hit.id())).toList();
            Set<Long> visible = repository.visible(entity, ids, scopes.rows(entity, caller));
            List<SearchHit> kept = group.hits().stream()
                    .filter(hit -> visible.contains(Long.parseLong(hit.id())))
                    .toList();
            long refused = group.hits().size() - kept.size();
            checked.add(new CollectionSearch(
                    group.entityType(),
                    kept.subList(0, Math.min(limit, kept.size())),
                    Math.max(kept.size(), group.found() - refused),
                    group.searchTimeMs()));
        }
        return checked;
    }

    private static List<CollectionSearch> mergeGroups(
            List<CollectionSearch> primary, List<CollectionSearch> secondary) {
        Map<String, CollectionSearch> map = new LinkedHashMap<>();
        for (CollectionSearch group : primary) {
            map.put(group.entityType(), group);
        }
        for (CollectionSearch sec : secondary) {
            CollectionSearch prim = map.get(sec.entityType());
            if (prim == null) {
                map.put(sec.entityType(), sec);
            } else {
                Set<String> existingIds =
                        prim.hits().stream().map(SearchHit::id).collect(Collectors.toSet());
                List<SearchHit> mergedHits = new ArrayList<>(prim.hits());
                for (SearchHit hit : sec.hits()) {
                    if (existingIds.add(hit.id())) {
                        mergedHits.add(hit);
                    }
                }
                map.put(
                        prim.entityType(),
                        new CollectionSearch(
                                prim.entityType(),
                                mergedHits,
                                Math.max(prim.found(), (long) mergedHits.size()),
                                Math.max(prim.searchTimeMs(), sec.searchTimeMs())));
            }
        }
        return List.copyOf(map.values());
    }

    static long sumFound(List<CollectionSearch> groups) {
        long total = 0;
        try {
            for (CollectionSearch group : groups) total = Math.addExact(total, group.found());
            return total;
        } catch (ArithmeticException overflow) {
            throw TypesenseException.invalidResponse();
        }
    }
}
