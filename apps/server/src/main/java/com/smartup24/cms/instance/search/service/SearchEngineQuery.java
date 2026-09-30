package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
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
 * The Typesense path of {@link SearchService}: the primary query, its language variants, and the PostgreSQL note
 * groups for ALL while notes have no collection. Failures surface as {@link TypesenseException} or a data access
 * exception so the caller can fall back.
 */
final class SearchEngineQuery {

    // The category stays the service's so existing log routing keeps matching.
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final TypesenseSearch typesense;
    private final SearchFallbackRepository fallbackRepository;

    SearchEngineQuery(TypesenseSearch typesense, SearchFallbackRepository fallbackRepository) {
        this.typesense = typesense;
        this.fallbackRepository = fallbackRepository;
    }

    boolean enabled() {
        return typesense.isEnabled();
    }

    List<CollectionSearch> groups(
            String cleanQuery,
            String cleanEntityType,
            int effectiveLimit,
            IndexSnapshot snapshot,
            SearchQueryPolicy currentPolicy,
            List<String> queryVariants) {
        if (!snapshot.initialized() || !hasCollections(cleanEntityType, snapshot.collections())) {
            throw TypesenseException.uninitialized();
        }
        List<CollectionSearch> groups = typesense.multiSearch(
                cleanQuery, cleanEntityType, effectiveLimit, snapshot.collections(), currentPolicy);

        int initialHits = groups.stream().mapToInt(g -> g.hits().size()).sum();
        if (initialHits < effectiveLimit && queryVariants.size() > 1) {
            for (int i = 1; i < queryVariants.size(); i++) {
                String variant = queryVariants.get(i);
                try {
                    List<CollectionSearch> variantGroups = typesense.multiSearch(
                            variant, cleanEntityType, effectiveLimit, snapshot.collections(), currentPolicy);
                    groups = mergeGroups(groups, variantGroups);
                } catch (RuntimeException variantFailed) {
                    // The main query answered; a failed spelling variant only narrows the hits.
                    log.debug("search_variant_failed error={}", variantFailed.toString());
                }
            }
        }

        if (cleanEntityType.equals("ALL")
                && (!snapshot.collections().containsKey("NOTE")
                        || snapshot.collections().get("NOTE").isBlank())) {
            groups = withFallbackNotes(groups, cleanQuery, queryVariants, effectiveLimit);
        }
        return groups;
    }

    private List<CollectionSearch> withFallbackNotes(
            List<CollectionSearch> groups, String cleanQuery, List<String> queryVariants, int effectiveLimit) {
        try {
            var noteSearch = fallbackRepository.search(cleanQuery, queryVariants, "NOTE", effectiveLimit);
            if (noteSearch != null && !noteSearch.groups().isEmpty()) {
                var noteGroup = noteSearch.groups().get(0);
                if (!noteGroup.hits().isEmpty()) {
                    groups = new ArrayList<>(groups);
                    groups.add(new CollectionSearch(
                            "NOTE",
                            noteGroup.hits().stream()
                                    .map(hit -> new SearchHit(
                                            hit.entityType(),
                                            hit.id(),
                                            hit.title(),
                                            hit.description(),
                                            hit.targetUrl()))
                                    .toList(),
                            noteGroup.hits().size(),
                            0));
                }
            }
        } catch (Exception ex) {
            log.debug("Note fallback query for ALL skipped: {}", ex.getMessage());
        }
        return groups;
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

    private static boolean hasCollections(String entityType, Map<String, String> collections) {
        List<String> needed = entityType.equals("ALL") ? List.of("TASK", "PROJECT", "USER") : List.of(entityType);
        return needed.stream()
                .allMatch(type ->
                        collections.containsKey(type) && !collections.get(type).isBlank());
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
