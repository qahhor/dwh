package com.greenwhite.dwh.instance.search.dto;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.Set;

/** The isolated strict boundary for search JSON. Legacy API mapper settings remain unchanged. */
public final class SearchManagementDtos {
    private SearchManagementDtos() {}
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public record SettingsSnapshot(long version, SearchQueryPolicy policy) {
        public SettingsSnapshot {
            if (version < 1 || policy == null) throw new IllegalArgumentException("Invalid settings snapshot");
        }
    }
    public record SaveSettingsRequest(long version, SearchQueryPolicy policy) {
        public SaveSettingsRequest {
            if (version < 1 || policy == null) throw new IllegalArgumentException("Invalid settings request");
        }
    }
    public record SearchExecutionSnapshot(IndexSnapshot index, SettingsSnapshot settings) {}
    public record PreviewRequest(String q, String entity, SearchQueryPolicy policy) {}
    public record PreviewResult(SearchResult result, String activeProfile) {}

    public static SaveSettingsRequest decodeSave(String json) {
        try {
            SaveSettingsRequest request = STRICT.readValue(json, SaveSettingsRequest.class);
            if (request == null) throw invalidRequest();
            return request;
        }
        catch (RuntimeException invalid) { throw invalidRequest(); }
    }

    public static PreviewRequest decodePreview(String json) {
        try {
            JsonNode node = STRICT.readTree(json);
            if (node == null || !node.isObject() || !Set.of("q", "entity", "policy").containsAll(node.propertyNames())
                    || !node.has("q") || !node.get("q").isString()
                    || (node.has("entity") && !node.get("entity").isString())
                    || (node.has("policy") && !node.get("policy").isObject())) throw invalidRequest();
            return new PreviewRequest(node.get("q").asString(), node.has("entity") ? node.get("entity").asString() : null,
                    node.has("policy") ? STRICT.treeToValue(node.get("policy"), SearchQueryPolicy.class) : null);
        } catch (RuntimeException invalid) { throw invalidRequest(); }
    }

    /** Only the original empty singleton seed is allowed to resolve to defaults. */
    public static SearchQueryPolicy decodeStored(String json, long version) {
        try {
            JsonNode node = STRICT.readTree(json);
            if (version == 1 && node != null && node.isObject() && node.isEmpty()) return SearchQueryPolicy.defaults();
            return STRICT.treeToValue(node, SearchQueryPolicy.class);
        } catch (RuntimeException invalid) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Search configuration is invalid");
        }
    }

    public static String encodePolicy(SearchQueryPolicy policy) { return STRICT.writeValueAsString(policy); }
    private static ApiException invalidRequest() { return ApiException.badRequest(ErrorCode.BAD_REQUEST, "Invalid search settings request"); }
}
