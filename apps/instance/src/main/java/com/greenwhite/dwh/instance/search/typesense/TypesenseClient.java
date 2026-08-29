package com.greenwhite.dwh.instance.search.typesense;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TypesenseClient {

    private static final Logger log = LoggerFactory.getLogger(TypesenseClient.class);

    public static final String COL_TASKS = "tasks";
    public static final String COL_PROJECTS = "projects";
    public static final String COL_USERS = "users";

    private final TypesenseProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TypesenseClient(TypesenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

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

    public List<SearchHit> search(String query, String entityType, int limit) {
        List<SearchHit> results = new ArrayList<>();
        if (!properties.enabled() || query == null || query.isBlank()) return results;

        boolean searchAll = entityType == null || entityType.isBlank() || entityType.equalsIgnoreCase("ALL");

        try {
            // 1. Tasks
            if (searchAll || entityType.equalsIgnoreCase("TASK")) {
                var hits = searchCollection(COL_TASKS, query, "title,description_markdown,status_name,project_name", limit, doc -> {
                    long id = doc.path("task_id").asLong(Long.parseLong(doc.path("id").asText("0")));
                    String title = doc.path("title").asText();
                    String status = doc.path("status_name").asText("Новая");
                    String priority = doc.path("priority").asText("medium");
                    String projectName = doc.path("project_name").asText("");
                    String snippet = "Статус: " + status + " | Приоритет: " + priority;
                    if (!projectName.isBlank()) {
                        snippet += " | Проект: " + projectName;
                    }
                    return new SearchHit("TASK", String.valueOf(id), title, snippet, "/tasks/items/" + id);
                });
                results.addAll(hits);
            }

            // 2. Projects
            if (searchAll || entityType.equalsIgnoreCase("PROJECT")) {
                var hits = searchCollection(COL_PROJECTS, query, "name,description", limit, doc -> {
                    long id = doc.path("project_id").asLong(Long.parseLong(doc.path("id").asText("0")));
                    String name = doc.path("name").asText();
                    String desc = doc.path("description").asText("");
                    return new SearchHit("PROJECT", String.valueOf(id), name, desc, "/tasks/projects/" + id);
                });
                results.addAll(hits);
            }

            // 3. Users
            if (searchAll || entityType.equalsIgnoreCase("USER")) {
                var hits = searchCollection(COL_USERS, query, "name,login,email,phone", limit, doc -> {
                    long id = doc.path("user_id").asLong(Long.parseLong(doc.path("id").asText("0")));
                    String name = doc.path("name").asText();
                    String email = doc.path("email").asText();
                    String login = doc.path("login").asText();
                    return new SearchHit("USER", String.valueOf(id), name, email + " (@" + login + ")", "/iam/users/" + id);
                });
                results.addAll(hits);
            }
        } catch (Exception e) {
            log.warn("Typesense: Ошибка выполнения поиска, будет использован Fallback: {}", e.getMessage());
            throw new RuntimeException("Typesense search failed", e);
        }

        return results;
    }

    private List<SearchHit> searchCollection(String collection, String query, String queryBy, int limit,
                                             java.util.function.Function<JsonNode, SearchHit> mapper) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            var res = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/collections/{collection}/documents/search")
                            .queryParam("q", query)
                            .queryParam("query_by", queryBy)
                            .queryParam("num_typos", 2)
                            .queryParam("prefix", true)
                            .queryParam("prioritize_exact_match", true)
                            .queryParam("per_page", limit)
                            .build(collection))
                    .retrieve()
                    .body(String.class);

            if (res != null) {
                JsonNode root = objectMapper.readTree(res);
                JsonNode hitsNode = root.path("hits");
                if (hitsNode.isArray()) {
                    for (JsonNode hit : hitsNode) {
                        JsonNode doc = hit.path("document");
                        hits.add(mapper.apply(doc));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Typesense: Ошибка поиска в коллекции '{}': {}", collection, e.getMessage());
        }
        return hits;
    }
}
