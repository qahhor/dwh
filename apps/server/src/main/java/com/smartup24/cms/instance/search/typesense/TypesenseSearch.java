package com.smartup24.cms.instance.search.typesense;

import com.smartup24.cms.instance.search.service.FieldPolicy;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Typesense queries: one multi-search per request across the collections of the entities the caller searches, each
 * filtered by the caller's scope keys (ADR-0032, 10.3). The hits are candidates: the caller checks them again in the
 * database before it answers them.
 */
@Component
public class TypesenseSearch {

    /** The most hits Typesense returns for one collection of a multi-search. */
    public static final int MAX_PER_PAGE = 250;

    private final TypesenseClient client;
    private final TypesenseSearchMapper searchMapper;

    public TypesenseSearch(TypesenseClient client) {
        this.client = client;
        this.searchMapper = new TypesenseSearchMapper(client.mapper());
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public List<CollectionSearch> multiSearch(String query, List<CollectionQuery> queries) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        if (queries.isEmpty()) return List.of();
        List<Map<String, Object>> searches =
                queries.stream().map(one -> searchRequest(query, one)).toList();
        try {
            String body = client.rest()
                    .post()
                    .uri("/multi_search")
                    .body(Map.of("searches", searches))
                    .retrieve()
                    .body(String.class);
            return searchMapper.map(
                    body, queries.stream().map(CollectionQuery::entity).toList());
        } catch (TypesenseException exception) {
            throw exception;
        } catch (Exception transportFailure) {
            throw TypesenseException.unavailable();
        }
    }

    private static Map<String, Object> searchRequest(String query, CollectionQuery one) {
        List<FieldPolicy> fields =
                one.fields().stream().filter(field -> field.weight() > 0).toList();
        if (one.collection().isBlank() || fields.isEmpty()) throw TypesenseException.uninitialized();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collection", one.collection());
        request.put("q", query);
        request.put("query_by", join(fields, FieldPolicy::field));
        request.put("query_by_weights", join(fields, field -> Integer.toString(field.weight())));
        request.put("num_typos", join(fields, field -> Integer.toString(field.numTypos())));
        request.put("prefix", join(fields, field -> Boolean.toString(field.prefix())));
        request.put("prioritize_exact_match", true);
        request.put("highlight_start_tag", "");
        request.put("highlight_end_tag", "");
        request.put("per_page", Math.max(1, Math.min(MAX_PER_PAGE, one.perPage())));
        if (one.filterBy() != null) request.put("filter_by", one.filterBy());
        return request;
    }

    private static String join(List<FieldPolicy> fields, Function<FieldPolicy, String> mapper) {
        return fields.stream().map(mapper).collect(Collectors.joining(","));
    }

    /**
     * The query of one entity's collection.
     *
     * @param entity     the entity whose records the collection holds
     * @param collection the collection of the active generation
     * @param fields     the weights of its searched fields
     * @param filterBy   the filter of the caller's scope keys, or null when the scope restricts nothing
     * @param perPage    how many candidates to ask for
     */
    public record CollectionQuery(
            SearchEntity entity,
            String collection,
            List<FieldPolicy> fields,
            @Nullable String filterBy,
            int perPage) {
        public CollectionQuery {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(collection, "collection");
            fields = List.copyOf(fields);
        }
    }

    public record CollectionSearch(String entityType, List<SearchHit> hits, long found, long searchTimeMs) {
        public CollectionSearch {
            hits = List.copyOf(hits);
        }
    }
}
