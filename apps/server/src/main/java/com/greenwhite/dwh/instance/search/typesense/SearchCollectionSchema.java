package com.greenwhite.dwh.instance.search.typesense;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typesense 27.1 fields. MIXED preserves the default tokenizer and disables stemming. */
public final class SearchCollectionSchema {
    private SearchCollectionSchema() {}

    public static Map<String,Object> mixed(String collection, String entityType) {
        List<Map<String,Object>> fields = new ArrayList<>();
        switch (entityType) {
            case "TASK" -> {
                fields.add(number("task_id", false));
                fields.add(text("title", false));
                fields.add(text("description_markdown", true));
                fields.add(text("status_name", true));
                fields.add(text("priority", true));
                fields.add(number("project_id", true));
                fields.add(text("project_name", true));
            }
            case "PROJECT" -> {
                fields.add(number("project_id", false));
                fields.add(text("name", false));
                fields.add(text("description", true));
                fields.add(Map.of("name", "state", "type", "string", "facet", true));
            }
            case "USER" -> {
                fields.add(number("user_id", false));
                fields.add(text("name", false));
                fields.add(text("login", false));
                fields.add(text("email", false));
                fields.add(text("phone", true));
                fields.add(Map.of("name", "state", "type", "string", "facet", true));
            }
            default -> throw new IllegalArgumentException("Unknown projection type");
        }
        fields.add(Map.of("name", "_projection_revision", "type", "int64", "index", false, "sort", false));
        fields.add(Map.of("name", "_projection_fingerprint", "type", "string", "index", false));
        return Map.of("name", collection, "fields", List.copyOf(fields));
    }

    private static Map<String,Object> text(String name, boolean optional) {
        return Map.of("name", name, "type", "string", "optional", optional, "stem", false);
    }
    private static Map<String,Object> number(String name, boolean optional) {
        return Map.of("name", name, "type", "int64", "optional", optional);
    }
}
