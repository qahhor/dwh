package com.smartup24.cms.instance.search.typesense;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Typesense collections: existence, creation from the registered schema, and schema drift observation. */
@Component
public class TypesenseCollections {

    public static final String COL_TASKS = "tasks";
    public static final String COL_PROJECTS = "projects";
    public static final String COL_USERS = "users";

    private final TypesenseClient client;

    public TypesenseCollections(TypesenseClient client) {
        this.client = client;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public boolean collectionExists(String name) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        try {
            client.rest().get().uri("/collections/{name}", name).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound missing) {
            return false;
        } catch (Exception failure) {
            throw TypesenseException.unavailable();
        }
    }

    public void ensureCollection(String name, String entityType) {
        ensureCollection(name, entityType, "MIXED");
    }

    public void ensureCollection(String name, String entityType, String profile) {
        if (collectionExists(name)) return;
        try {
            client.rest()
                    .post()
                    .uri("/collections")
                    .body(SearchCollectionSchema.forProfile(name, entityType, profile))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception failure) {
            throw TypesenseException.unavailable();
        }
    }

    public CollectionMetadata observeCollection(String collection, String entityType, String registeredProfile) {
        if (!client.isEnabled()) return new CollectionMetadata(null, null, null, "DEPENDENCY_UNAVAILABLE");
        try {
            var schema = client.metadata("/collections/{collection}", collection);
            Long count = TypesenseClient.nonnegativeInteger(schema.path("num_documents"), false);
            Boolean matches = matchesSchema(schema, collection, entityType, registeredProfile);
            return new CollectionMetadata(
                    count, null, matches, count == null ? "COLLECTION_METADATA_UNAVAILABLE" : null);
        } catch (HttpClientErrorException.NotFound missing) {
            return new CollectionMetadata(null, null, false, "COLLECTION_MISSING");
        } catch (RuntimeException unavailable) {
            return new CollectionMetadata(null, null, null, "COLLECTION_METADATA_UNAVAILABLE");
        }
    }

    private boolean matchesSchema(JsonNode actual, String collection, String entityType, String profile) {
        for (String property : List.of("token_separators", "symbols_to_index")) {
            if (actual.has(property)
                    && (!actual.get(property).isArray() || !actual.get(property).isEmpty())) return false;
        }
        if (actual.has("default_sorting_field")
                && (!actual.get("default_sorting_field").isString()
                        || !actual.get("default_sorting_field").asString().isEmpty())) return false;
        var expected = client.mapper()
                .valueToTree(SearchCollectionSchema.forProfile(collection, entityType, profile))
                .path("fields");
        if (!actual.path("fields").isArray()) return false;
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (var field : actual.path("fields")) {
            if (!field.path("name").isString() || fields.put(field.path("name").asString(), field) != null)
                return false;
        }
        fields.remove("id"); // Typesense may include the implicit document identifier.
        if (fields.size() != expected.size()) return false;
        for (var field : expected) {
            if (!fieldMatches(field, fields.get(field.path("name").asString()))) return false;
        }
        return true;
    }

    private static boolean fieldMatches(JsonNode field, JsonNode observed) {
        if (observed == null || !field.path("type").equals(observed.path("type"))) return false;
        boolean numeric = field.path("type").asString().equals("int64");
        for (var property : List.of("optional", "facet", "index", "sort", "store", "stem")) {
            boolean defaultValue =
                    property.equals("index") || property.equals("store") || (property.equals("sort") && numeric);
            boolean wanted = field.has(property) ? field.get(property).asBoolean() : defaultValue;
            if (observed.has(property)
                    && (!observed.get(property).isBoolean()
                            || observed.get(property).asBoolean() != wanted)) return false;
            if (!observed.has(property) && wanted != defaultValue) return false;
        }
        String wantedLocale = field.has("locale") ? field.get("locale").asString() : "";
        if (observed.has("locale")
                && (!observed.get("locale").isString()
                        || !observed.get("locale").asString().equals(wantedLocale))) return false;
        return observed.has("locale") || wantedLocale.isEmpty();
    }

    public record CollectionMetadata(Long documentCount, Long storageBytes, Boolean schemaMatches, String errorCode) {}
}
