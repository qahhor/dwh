package com.smartup24.cms.instance.search.typesense;

import com.smartup24.cms.instance.search.repository.SearchDocumentSql;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Typesense 27.1 collection of an entity the search finds, built from its search spec (ADR-0032, 10.3): the
 * record's id, the searched fields as strings — the title required, the others optional — and the scope keys as
 * number arrays the queries filter by. {@code MIXED} keeps the default tokenizer without stemming; {@code RU} stems
 * the fields of natural language in Russian.
 */
public final class SearchCollectionSchema {
    private SearchCollectionSchema() {}

    public static Map<String, Object> mixed(String collection, SearchEntity entity) {
        return forProfile(collection, entity, "MIXED");
    }

    public static Map<String, Object> forProfile(String collection, SearchEntity entity, String profile) {
        if (!List.of("MIXED", "RU").contains(profile)) throw new IllegalArgumentException("Unknown schema profile");
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("name", SearchDocumentSql.RECORD_ID, "type", "int64", "optional", false));
        boolean title = true;
        for (EntityField field : entity.fields()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("name", field.key());
            text.put("type", "string");
            text.put("optional", !title);
            text.put("stem", false);
            if ("RU".equals(profile) && SearchEntity.naturalLanguage(field)) {
                text.put("locale", "ru");
                text.put("stem", true);
            }
            fields.add(Map.copyOf(text));
            title = false;
        }
        fields.add(Map.of("name", SearchDocumentSql.SCOPE_USERS, "type", "int64[]", "optional", true, "sort", false));
        fields.add(Map.of("name", SearchDocumentSql.SCOPE_UNITS, "type", "int64[]", "optional", true, "sort", false));
        fields.add(Map.of("name", "_projection_revision", "type", "int64", "index", false, "sort", false));
        fields.add(Map.of("name", "_projection_fingerprint", "type", "string", "index", false));
        return Map.of("name", collection, "fields", List.copyOf(fields));
    }
}
