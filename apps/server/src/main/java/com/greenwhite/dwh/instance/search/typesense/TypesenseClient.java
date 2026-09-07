package com.greenwhite.dwh.instance.search.typesense;

import tools.jackson.databind.ObjectMapper;

import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.service.FieldPolicy;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TypesenseClient {

    private static final Logger log = LoggerFactory.getLogger(TypesenseClient.class);

    public static final String COL_TASKS = "tasks";
    public static final String COL_PROJECTS = "projects";
    public static final String COL_USERS = "users";

    private final TypesenseProperties properties;
    private final RestClient restClient;
    private final TypesenseSearchMapper searchMapper;

    public TypesenseClient(TypesenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.searchMapper = new TypesenseSearchMapper(objectMapper);

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(1500));
        requestFactory.setReadTimeout(Duration.ofMillis(3000));

        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .defaultHeader("X-TYPESENSE-API-KEY", properties.apiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public boolean isHealthy() {
        if (!properties.enabled()) return false;
        try {
            var response = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
            return response != null && response.contains("ok");
        } catch (Exception e) {
            log.debug("Typesense health check unavailable");
            return false;
        }
    }

    public void upsertDocument(String collection, Map<String, Object> document) {
        if (!properties.enabled()) throw TypesenseException.uninitialized();
        try {
            restClient.post()
                    .uri("/collections/{collection}/documents?action=upsert", collection)
                    .body(document)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw TypesenseException.unavailable();
        }
    }

    public void deleteDocument(String collection, String documentId) {
        if (!properties.enabled()) throw TypesenseException.uninitialized();
        try {
            restClient.delete()
                    .uri("/collections/{collection}/documents/{id}", collection, documentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound missing) {
            // A document 404 is idempotent success only while its collection still exists.
            if (!collectionExists(collection)) throw TypesenseException.uninitialized();
        } catch (Exception e) {
            throw TypesenseException.unavailable();
        }
    }

    public boolean collectionExists(String name) {
        if (!properties.enabled()) throw TypesenseException.uninitialized();
        try {
            restClient.get().uri("/collections/{name}", name).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound missing) {
            return false;
        } catch (Exception failure) {
            throw TypesenseException.unavailable();
        }
    }

    public void ensureCollection(String name, String entityType) {
        if (collectionExists(name)) return;
        try {
            restClient.post().uri("/collections").body(SearchCollectionSchema.mixed(name, entityType))
                    .retrieve().toBodilessEntity();
        } catch (Exception failure) {
            throw TypesenseException.unavailable();
        }
    }

    public List<CollectionSearch> multiSearch(
            String query,
            String entityType,
            int limit,
            Map<String, String> collections,
            SearchQueryPolicy policy) {
        if (!properties.enabled()) throw TypesenseException.uninitialized();
        List<String> requestedTypes = requestedTypes(entityType);
        List<Map<String, Object>> searches = requestedTypes.stream()
                .map(type -> searchRequest(query, type, limit, collections, policy))
                .toList();

        try {
            String body = restClient.post()
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

    private static List<String> requestedTypes(String entityType) {
        String normalized = entityType == null ? "ALL" : entityType.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALL" -> List.of("TASK", "PROJECT", "USER");
            case "TASK", "PROJECT", "USER" -> List.of(normalized);
            default -> throw TypesenseException.uninitialized();
        };
    }

    private static Map<String, Object> searchRequest(
            String query,
            String entityType,
            int limit,
            Map<String, String> collections,
            SearchQueryPolicy policy) {
        String collection = collections.get(entityType);
        List<FieldPolicy> fields = policy.fields().get(entityType);
        if (collection == null || collection.isBlank() || fields == null || fields.isEmpty()) {
            throw TypesenseException.uninitialized();
        }
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

    private static String join(List<FieldPolicy> fields, java.util.function.Function<FieldPolicy, String> mapper) {
        return fields.stream().map(mapper).collect(Collectors.joining(","));
    }

    public record CollectionSearch(String entityType, List<SearchHit> hits, long found, long searchTimeMs) {
        public CollectionSearch {
            hits = List.copyOf(hits);
        }
    }
}
