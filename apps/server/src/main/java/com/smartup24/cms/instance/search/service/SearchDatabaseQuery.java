package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository.FallbackHit;
import com.smartup24.cms.instance.search.service.SearchScopes.Caller;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.ArrayList;
import java.util.List;

/**
 * The PostgreSQL path of {@link SearchService} (ADR-0032, 10.3): each entity the caller searches is read from its own
 * table with the caller's scope predicate, so it needs no check afterwards. It answers when Typesense is off, not
 * initialized or failing, an exact {@code #id} query, and an entity that has no collection yet.
 */
final class SearchDatabaseQuery {

    private final SearchFallbackRepository repository;
    private final SearchScopes scopes;

    SearchDatabaseQuery(SearchFallbackRepository repository, SearchScopes scopes) {
        this.repository = repository;
        this.scopes = scopes;
    }

    /**
     * The groups of a text query, one per entity; {@link CollectionSearch#found()} is one more than the hits when there
     * are more.
     */
    List<CollectionSearch> groups(
            String query, List<String> variants, List<SearchEntity> targets, int limit, Caller caller) {
        List<CollectionSearch> groups = new ArrayList<>(targets.size());
        for (SearchEntity entity : targets) {
            List<FallbackHit> candidates =
                    repository.search(entity, query, variants, limit + 1, scopes.rows(entity, caller));
            List<SearchHit> hits = candidates.subList(0, Math.min(limit, candidates.size())).stream()
                    .map(SearchDatabaseQuery::hit)
                    .toList();
            groups.add(new CollectionSearch(entity.code(), hits, candidates.size(), 0));
        }
        return groups;
    }

    /** The groups of an exact {@code #id} query: the record of each entity with that id the caller may see. */
    List<CollectionSearch> exact(long id, List<SearchEntity> targets, Caller caller) {
        List<CollectionSearch> groups = new ArrayList<>(targets.size());
        for (SearchEntity entity : targets) {
            List<SearchHit> hits = repository.exact(entity, id, scopes.rows(entity, caller)).stream()
                    .map(SearchDatabaseQuery::hit)
                    .toList();
            groups.add(new CollectionSearch(entity.code(), hits, hits.size(), 0));
        }
        return groups;
    }

    private static SearchHit hit(FallbackHit hit) {
        return new SearchHit(hit.entityType(), hit.id(), hit.title(), hit.description(), hit.targetUrl());
    }
}
