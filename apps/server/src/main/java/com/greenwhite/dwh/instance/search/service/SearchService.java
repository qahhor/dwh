package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository.FallbackSearch;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.CollectionSearch;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final TypesenseClient typesenseClient;
    private final SearchFallbackRepository fallbackRepository;
    private final SearchAccessPolicy accessPolicy;
    private final SearchResultBudget resultBudget;
    private final Supplier<SearchQueryPolicy> queryPolicy;
    private final Supplier<IndexSnapshot> indexSnapshot;

    @Autowired
    public SearchService(TypesenseClient typesenseClient, SearchFallbackRepository fallbackRepository,
                         SearchAccessPolicy accessPolicy, SearchResultBudget resultBudget,
                         SearchPolicyProvider policyProvider,
                         SearchIndexStateRepository state) {
        this(typesenseClient, fallbackRepository, accessPolicy, resultBudget,
                policyProvider::current, state::snapshot);
    }

    /** Policy/snapshot boundary shared with the later saved-settings provider. */
    protected SearchService(TypesenseClient typesenseClient, SearchFallbackRepository fallbackRepository,
                            SearchAccessPolicy accessPolicy, SearchResultBudget resultBudget,
                            SearchQueryPolicy queryPolicy, Supplier<IndexSnapshot> indexSnapshot) {
        this(typesenseClient, fallbackRepository, accessPolicy, resultBudget, () -> queryPolicy, indexSnapshot);
    }

    private SearchService(TypesenseClient typesenseClient, SearchFallbackRepository fallbackRepository,
                          SearchAccessPolicy accessPolicy, SearchResultBudget resultBudget,
                          Supplier<SearchQueryPolicy> queryPolicy, Supplier<IndexSnapshot> indexSnapshot) {
        this.typesenseClient = typesenseClient;
        this.fallbackRepository = fallbackRepository;
        this.accessPolicy = accessPolicy;
        this.resultBudget = resultBudget;
        this.queryPolicy = queryPolicy;
        this.indexSnapshot = indexSnapshot;
    }

    public SearchResult search(String query, String entityType, int limit) {
        accessPolicy.requireSearchAccess();
        String cleanQuery = normalizeQuery(query);
        String cleanEntityType = normalizeEntityType(entityType);
        SearchQueryPolicy currentPolicy = queryPolicy.get();
        int effectiveLimit = effectiveLimit(limit, currentPolicy);

        Long exactId = exactId(cleanQuery);
        if (exactId != null) {
            try {
                return fallbackResult(cleanQuery, fallbackRepository.searchExact(exactId, cleanEntityType),
                        effectiveLimit, false, true);
            } catch (Exception exactFailure) {
                throw unavailable();
            }
        }

        if (typesenseClient.isEnabled()) {
            try {
                IndexSnapshot snapshot = indexSnapshot.get();
                if (!snapshot.initialized() || !hasCollections(cleanEntityType, snapshot.collections())) {
                    throw TypesenseException.uninitialized();
                }
                List<CollectionSearch> groups = typesenseClient.multiSearch(
                        cleanQuery, cleanEntityType, effectiveLimit, snapshot.collections(), currentPolicy);
                List<SearchHit> hits = resultBudget.allocate(groups, effectiveLimit);
                long found = sumFound(groups);
                return new SearchResult(cleanQuery, hits.size(), hits, found,
                        found > hits.size(), "TYPESENSE", false);
            } catch (TypesenseException | org.springframework.dao.DataAccessException unavailableOrInvalid) {
                log.warn("Typesense search failed; using PostgreSQL fallback");
            }
        }

        try {
            return fallbackResult(cleanQuery, fallbackRepository.search(cleanQuery, cleanEntityType, effectiveLimit),
                    effectiveLimit, true, false);
        } catch (Exception fallbackFailure) {
            throw unavailable();
        }
    }

    private SearchResult fallbackResult(String query, FallbackSearch fallback, int effectiveLimit,
                                        boolean degraded, boolean countKnown) {
        List<CollectionSearch> groups = fallback.groups().stream()
                .map(group -> new CollectionSearch(group.entityType(), group.hits().stream()
                        .map(hit -> new SearchHit(hit.entityType(), hit.id(), hit.title(), hit.description(), hit.targetUrl()))
                        .toList(), group.hits().size(), 0))
                .toList();
        List<SearchHit> hits = resultBudget.allocate(groups, effectiveLimit);
        int available = groups.stream().mapToInt(group -> group.hits().size()).sum();
        boolean hasMore = fallback.groups().stream().anyMatch(group -> group.hasMore())
                || available > hits.size();
        Long foundHits = countKnown ? (long) available : null;
        return new SearchResult(query, hits.size(), hits, foundHits, hasMore, "POSTGRES", degraded);
    }

    private static int effectiveLimit(int requestedLimit, SearchQueryPolicy queryPolicy) {
        if (requestedLimit < 1 || requestedLimit > 50) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Лимит поиска должен быть от 1 до 50");
        }
        return Math.min(requestedLimit, queryPolicy.globalLimit());
    }

    private static String normalizeQuery(String query) {
        if (query == null) throw invalidQuery();
        String clean = query.trim();
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 200) throw invalidQuery();
        return clean;
    }

    private static ApiException invalidQuery() {
        return ApiException.badRequest(ErrorCode.EMPTY_QUERY,
                "Поисковый запрос должен содержать от 2 до 200 символов");
    }

    private static String normalizeEntityType(String entityType) {
        if (entityType == null) return "ALL";
        String normalized = entityType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALL", "TASK", "PROJECT", "USER" -> normalized;
            default -> throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Неизвестная категория поиска");
        };
    }

    private static Long exactId(String query) {
        if (!query.matches("#[0-9]+")) return null;
        try {
            long id = Long.parseLong(query.substring(1));
            if (id <= 0) throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "ID должен быть положительным числом");
            return id;
        } catch (NumberFormatException overflow) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Некорректный ID поиска");
        }
    }

    private boolean hasCollections(String entityType, Map<String,String> collections) {
        List<String> needed = entityType.equals("ALL") ? List.of("TASK", "PROJECT", "USER") : List.of(entityType);
        return needed.stream().allMatch(type -> collections.containsKey(type) && !collections.get(type).isBlank());
    }

    private static long sumFound(List<CollectionSearch> groups) {
        long total = 0;
        try {
            for (CollectionSearch group : groups) total = Math.addExact(total, group.found());
            return total;
        } catch (ArithmeticException overflow) {
            throw TypesenseException.invalidResponse();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Поиск временно недоступен");
    }

    public record SearchHit(String entityType, String id, String title, String description, String targetUrl) {}
    public record SearchResult(String query, int totalHits, List<SearchHit> hits, Long foundHits,
                               boolean hasMore, String source, boolean degraded) {
        public SearchResult { hits = List.copyOf(hits); }
    }
}
