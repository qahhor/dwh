package com.greenwhite.dwh.instance.search.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable search behavior policy; never exposes raw Typesense request parameters. */
public record SearchQueryPolicy(
        int globalLimit,
        int requestsPerMinute,
        int burst,
        String schemaProfile,
        Map<String, List<FieldPolicy>> fields) {

    public SearchQueryPolicy {
        if (globalLimit < 1 || globalLimit > 50) throw new IllegalArgumentException("Global search limit must be between 1 and 50");
        if (requestsPerMinute < 1 || burst < 1) throw new IllegalArgumentException("Search rate limits must be positive");
        if (schemaProfile == null || schemaProfile.isBlank()) throw new IllegalArgumentException("Search schema profile is required");
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("Search fields are required");
        var copy = new LinkedHashMap<String, List<FieldPolicy>>();
        fields.forEach((entityType, policies) -> copy.put(entityType, List.copyOf(policies)));
        fields = java.util.Collections.unmodifiableMap(copy);
    }

    public static SearchQueryPolicy defaults() {
        var fields = new LinkedHashMap<String, List<FieldPolicy>>();
        fields.put("TASK", List.of(
                new FieldPolicy("title", 10, 2, true),
                new FieldPolicy("description_markdown", 3, 2, true),
                new FieldPolicy("status_name", 2, 2, true),
                new FieldPolicy("project_name", 2, 2, true)));
        fields.put("PROJECT", List.of(
                new FieldPolicy("name", 10, 2, true),
                new FieldPolicy("description", 3, 2, true)));
        fields.put("USER", List.of(
                new FieldPolicy("name", 10, 2, true),
                new FieldPolicy("login", 8, 0, true),
                new FieldPolicy("email", 6, 0, true),
                new FieldPolicy("phone", 6, 0, true)));
        return new SearchQueryPolicy(10, 120, 20, "MIXED", fields);
    }
}
