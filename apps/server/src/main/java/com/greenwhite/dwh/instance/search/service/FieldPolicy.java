package com.greenwhite.dwh.instance.search.service;

/** Typed Typesense field configuration shared by query construction and saved policy. */
public record FieldPolicy(String field, int weight, int numTypos, boolean prefix) {

    public FieldPolicy {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("Search field is required");
        if (weight < 0 || weight > 127) throw new IllegalArgumentException("Search field weight must be between 0 and 127");
        if (numTypos < 0 || numTypos > 2) throw new IllegalArgumentException("Search field typo count must be between 0 and 2");
    }
}
