package com.smartup24.cms.instance.search.typesense;

import com.smartup24.cms.instance.search.service.FieldPolicy;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Typesense queries: one multi-search per request across the collections of the active generation. */
@Component
public class TypesenseSearch {

    private final TypesenseClient client;
    private final TypesenseSearchMapper searchMapper;

    public TypesenseSearch(TypesenseClient client) {
        this.client = client;
        this.searchMapper = new TypesenseSearchMapper(client.mapper());
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public List<CollectionSearch> multiSearch(
            String query, String entityType, int limit, Map<String, String> collections, SearchQueryPolicy policy) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        List<String> requestedTypes = requestedTypes(entityType, collections);
        List<Map<String, Object>> searches = requestedTypes.stream()
                .map(type -> searchRequest(query, type, limit, collections, policy))
                .toList();

        try {
            String body = client.rest()
                    .post()
                    .uri("/multi_search")
                    .body(Map.of("searches", searches))
                    .retrieve()
                    .body(String.class);
            return searchMapper.map(body, requestedTypes);
        } catch (TypesenseException exception) {
            throw exception;
        } catch (Exception transportFailure) {
            throw TypesenseException.unavailable();
        }
    }

    private static List<String> requestedTypes(String entityType, Map<String, String> collections) {
        String normalized = entityType == null ? "ALL" : entityType.toUpperCase(Locale.ROOT);
        if (normalized.equals("ALL")) {
            List<String> types = new ArrayList<>(List.of("TASK", "PROJECT", "USER"));
            if (collections != null
                    && collections.containsKey("NOTE")
                    && !collections.get("NOTE").isBlank()) {
                types.add("NOTE");
            }
            return List.copyOf(types);
        }
        return switch (normalized) {
            case "TASK", "PROJECT", "USER", "NOTE" -> List.of(normalized);
            default -> throw TypesenseException.uninitialized();
        };
    }

    private static Map<String, Object> searchRequest(
            String query, String entityType, int limit, Map<String, String> collections, SearchQueryPolicy policy) {
        String collection = collections.get(entityType);
        List<FieldPolicy> fields = policy.fields().get(entityType);
        if (collection == null || collection.isBlank() || fields == null || fields.isEmpty()) {
            throw TypesenseException.uninitialized();
        }
        fields = fields.stream().filter(field -> field.weight() > 0).toList();
        if (fields.isEmpty()) throw TypesenseException.uninitialized();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collection", collection);
        request.put("q", query);
        request.put("query_by", join(fields, field -> field.field()));
        request.put("query_by_weights", join(fields, field -> Integer.toString(field.weight())));
        request.put("num_typos", join(fields, field -> Integer.toString(field.numTypos())));
        request.put("prefix", join(fields, field -> Boolean.toString(field.prefix())));
        request.put("prioritize_exact_match", true);
        request.put("highlight_start_tag", "");
        request.put("highlight_end_tag", "");
        request.put("per_page", limit);
        if (entityType.equals("PROJECT") || entityType.equals("USER")) request.put("filter_by", "state:=A");
        return request;
    }

    private static String join(List<FieldPolicy> fields, Function<FieldPolicy, String> mapper) {
        return fields.stream().map(mapper).collect(Collectors.joining(","));
    }

    public record CollectionSearch(String entityType, List<SearchHit> hits, long found, long searchTimeMs) {
        public CollectionSearch {
            hits = List.copyOf(hits);
        }
    }
}
