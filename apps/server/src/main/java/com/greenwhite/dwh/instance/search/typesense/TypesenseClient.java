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
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 3000;

    public static final String COL_TASKS = "tasks";
    public static final String COL_PROJECTS = "projects";
    public static final String COL_USERS = "users";

    private final TypesenseProperties properties;
    private final RestClient restClient;
    private final TypesenseSearchMapper searchMapper;
    private final ObjectMapper objectMapper;

    public TypesenseClient(TypesenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.searchMapper = new TypesenseSearchMapper(objectMapper);

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        requestFactory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));

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
            var response = metadata("/health");
            return response.path("ok").isBoolean() && response.path("ok").asBoolean();
        } catch (Exception e) {
            log.debug("Typesense health check unavailable");
            return false;
        }
    }

    public DependencyMetadata observeDependency() {
        if (!isEnabled()) return new DependencyMetadata(false, false, null, null, null, "DISABLED");
        if (!isHealthy()) return new DependencyMetadata(true, false, null, null, null, "DEPENDENCY_UNAVAILABLE");
        String version = null;
        Long used = null;
        Long total = null;
        String error = null;
        try {
            var value = metadata("/debug").path("version");
            if (value.isString() && value.asString().length() <= 48
                    && value.asString().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+][A-Za-z0-9.-]+)?")) version = value.asString();
            else error = "METADATA_UNAVAILABLE";
        } catch (RuntimeException unavailable) { error = "METADATA_UNAVAILABLE"; }
        try {
            var metrics = metadata("/metrics.json");
            used = nonnegativeInteger(metrics.path("system_disk_used_bytes"), true);
            total = nonnegativeInteger(metrics.path("system_disk_total_bytes"), true);
            if (used == null || total == null) error = "METADATA_UNAVAILABLE";
        } catch (RuntimeException unavailable) { error = "METADATA_UNAVAILABLE"; }
        return new DependencyMetadata(true, true, version, used, total, error);
    }

    public CollectionMetadata observeCollection(String collection, String entityType, String registeredProfile) {
        if (!isEnabled()) return new CollectionMetadata(null, null, null, "DEPENDENCY_UNAVAILABLE");
        try {
            var schema = metadata("/collections/{collection}", collection);
            Long count = nonnegativeInteger(schema.path("num_documents"), false);
            // RU comparison uses the shared profile-aware schema builder in the generation-management task.
            Boolean matches = "MIXED".equals(registeredProfile) ? matchesMixedSchema(schema, collection, entityType) : null;
            return new CollectionMetadata(count, null, matches,
                    count == null ? "COLLECTION_METADATA_UNAVAILABLE" : null);
        } catch (HttpClientErrorException.NotFound missing) {
            return new CollectionMetadata(null, null, false, "COLLECTION_MISSING");
        } catch (RuntimeException unavailable) {
            return new CollectionMetadata(null, null, null, "COLLECTION_METADATA_UNAVAILABLE");
        }
    }

    public TransportBudgets transportBudgets() { return new TransportBudgets(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS); }

    private tools.jackson.databind.JsonNode metadata(String uri, Object... variables) {
        String body = restClient.get().uri(uri, variables).retrieve().body(String.class);
        var value = objectMapper.readTree(body);
        if (value == null || !value.isObject()) throw TypesenseException.invalidResponse();
        return value;
    }

    private boolean matchesMixedSchema(tools.jackson.databind.JsonNode actual, String collection, String entityType) {
        for (String property : List.of("token_separators", "symbols_to_index")) {
            if (actual.has(property) && (!actual.get(property).isArray() || !actual.get(property).isEmpty())) return false;
        }
        if (actual.has("default_sorting_field") && (!actual.get("default_sorting_field").isString()
                || !actual.get("default_sorting_field").asString().isEmpty())) return false;
        var expected = objectMapper.valueToTree(SearchCollectionSchema.mixed(collection, entityType)).path("fields");
        if (!actual.path("fields").isArray()) return false;
        Map<String, tools.jackson.databind.JsonNode> fields = new LinkedHashMap<>();
        for (var field : actual.path("fields")) {
            if (!field.path("name").isString() || fields.put(field.path("name").asString(), field) != null) return false;
        }
        fields.remove("id"); // Typesense may include the implicit document identifier.
        if (fields.size() != expected.size()) return false;
        for (var field : expected) {
            var observed = fields.get(field.path("name").asString());
            if (observed == null || !field.path("type").equals(observed.path("type"))) return false;
            boolean numeric = field.path("type").asString().equals("int64");
            for (var property : List.of("optional", "facet", "index", "sort", "store", "stem")) {
                boolean defaultValue = property.equals("index") || property.equals("store") || (property.equals("sort") && numeric);
                boolean wanted = field.has(property) ? field.get(property).asBoolean() : defaultValue;
                if (observed.has(property) && (!observed.get(property).isBoolean() || observed.get(property).asBoolean() != wanted)) return false;
                if (!observed.has(property) && wanted != defaultValue) return false;
            }
            if (observed.has("locale") && (!observed.get("locale").isString() || !observed.get("locale").asString().isEmpty())) return false;
        }
        return true;
    }

    private static Long nonnegativeInteger(tools.jackson.databind.JsonNode value, boolean decimalStringAllowed) {
        try {
            if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0) return value.asLong();
            if (decimalStringAllowed && value.isString() && value.asString().matches("[0-9]+(?:\\.0+)?")) {
                long result = new java.math.BigDecimal(value.asString()).longValueExact();
                return result >= 0 ? result : null;
            }
        } catch (ArithmeticException overflow) { /* unavailable counter */ }
        return null;
    }

    public record DependencyMetadata(boolean enabled, boolean healthy, String version,
                                     Long installationDiskUsedBytes, Long installationDiskTotalBytes, String errorCode) {}
    public record CollectionMetadata(Long documentCount, Long storageBytes, Boolean schemaMatches, String errorCode) {}
    public record TransportBudgets(int connectTimeoutMs, int readTimeoutMs) {}

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

    private static String join(List<FieldPolicy> fields, java.util.function.Function<FieldPolicy, String> mapper) {
        return fields.stream().map(mapper).collect(Collectors.joining(","));
    }

    public record CollectionSearch(String entityType, List<SearchHit> hits, long found, long searchTimeMs) {
        public CollectionSearch {
            hits = List.copyOf(hits);
        }
    }
}
