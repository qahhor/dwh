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
            log.debug("Typesense недоступен: {}", e.getMessage());
            return false;
        }
    }

    public void initCollections() {
        if (!properties.enabled()) return;
        try {
            ensureCollection(COL_TASKS, List.of(
                    Map.of("name", "id", "type", "string"),
                    Map.of("name", "task_id", "type", "int64"),
                    Map.of("name", "title", "type", "string", "enable_phonetic", true),
                    Map.of("name", "description_markdown", "type", "string", "optional", true),
                    Map.of("name", "status_name", "type", "string", "optional", true),
                    Map.of("name", "priority", "type", "string", "optional", true),
                    Map.of("name", "project_id", "type", "int64", "optional", true),
                    Map.of("name", "project_name", "type", "string", "optional", true)
            ));

            ensureCollection(COL_PROJECTS, List.of(
                    Map.of("name", "id", "type", "string"),
                    Map.of("name", "project_id", "type", "int64"),
                    Map.of("name", "name", "type", "string", "enable_phonetic", true),
                    Map.of("name", "description", "type", "string", "optional", true),
                    Map.of("name", "state", "type", "string", "optional", true)
            ));

            ensureCollection(COL_USERS, List.of(
                    Map.of("name", "id", "type", "string"),
                    Map.of("name", "user_id", "type", "int64"),
                    Map.of("name", "name", "type", "string", "enable_phonetic", true),
                    Map.of("name", "login", "type", "string"),
                    Map.of("name", "email", "type", "string"),
                    Map.of("name", "phone", "type", "string", "optional", true),
                    Map.of("name", "state", "type", "string", "optional", true)
            ));
            log.info("Typesense: Схемы коллекций успешно проверены и инициализированы.");
        } catch (Exception e) {
            log.warn("Typesense: Ошибка при инициализации коллекций: {}", e.getMessage());
        }
    }

    private void ensureCollection(String name, List<Map<String, Object>> fields) {
        try {
            restClient.get().uri("/collections/{name}", name).retrieve().toBodilessEntity();
        } catch (Exception notFound) {
            Map<String, Object> schema = Map.of(
                    "name", name,
                    "fields", fields,
                    "enable_nested_fields", true
            );
            restClient.post()
                    .uri("/collections")
                    .body(schema)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Typesense: Создана новая коллекция '{}'", name);
        }
    }

    public void upsertDocument(String collection, Map<String, Object> document) {
        if (!properties.enabled()) return;
        try {
            restClient.post()
                    .uri("/collections/{collection}/documents?action=upsert", collection)
                    .body(document)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Typesense: Ошибка индексации документа в '{}': {}", collection, e.getMessage());
        }
    }

    public void deleteDocument(String collection, String documentId) {
        if (!properties.enabled()) return;
        try {
            restClient.delete()
                    .uri("/collections/{collection}/documents/{id}", collection, documentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Typesense: Ошибка удаления документа '{}' из '{}': {}", documentId, collection, e.getMessage());
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
