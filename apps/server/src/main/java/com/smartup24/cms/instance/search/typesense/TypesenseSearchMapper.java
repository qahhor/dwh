package com.smartup24.cms.instance.search.typesense;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.search.repository.SearchDocumentSql;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Strictly validates and maps one Typesense multi-search response as one atomic operation. A hit is named by the
 * entity's title field and described by its highlight or the first filled field of the search spec (ADR-0032, 10.3).
 */
public final class TypesenseSearchMapper {

    private static final int MAX_SNIPPET_CODE_POINTS = 240;

    private final ObjectMapper objectMapper;

    public TypesenseSearchMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<CollectionSearch> map(String body, List<SearchEntity> entities) {
        if (body == null || body.isBlank()) throw TypesenseException.invalidResponse();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.size() != entities.size()) {
                throw TypesenseException.invalidResponse();
            }
            List<CollectionSearch> mapped = new ArrayList<>(entities.size());
            for (int i = 0; i < entities.size(); i++) {
                mapped.add(mapCollection(entities.get(i), results.get(i)));
            }
            return List.copyOf(mapped);
        } catch (TypesenseException exception) {
            throw exception;
        } catch (Exception invalidJsonOrShape) {
            throw TypesenseException.invalidResponse();
        }
    }

    private CollectionSearch mapCollection(SearchEntity entity, JsonNode result) {
        if (result == null || !result.isObject() || result.has("error") || result.has("code")) {
            throw TypesenseException.invalidResponse();
        }
        long found = requiredNonNegativeLong(result, "found");
        long searchTimeMs = requiredNonNegativeLong(result, "search_time_ms");
        JsonNode hitsNode = result.get("hits");
        if (hitsNode == null || !hitsNode.isArray() || found < hitsNode.size()) {
            throw TypesenseException.invalidResponse();
        }
        List<SearchHit> hits = new ArrayList<>(hitsNode.size());
        for (JsonNode hit : hitsNode) hits.add(mapHit(entity, hit));
        return new CollectionSearch(entity.code(), hits, found, searchTimeMs);
    }

    private SearchHit mapHit(SearchEntity entity, JsonNode hit) {
        if (hit == null || !hit.isObject()) throw TypesenseException.invalidResponse();
        JsonNode document = hit.get("document");
        if (document == null || !document.isObject()) throw TypesenseException.invalidResponse();
        long id = requiredDocumentId(document);
        List<EntityField> fields = entity.fields();
        String title = optionalText(document, fields.getFirst().key());
        String fallback = "";
        for (EntityField field : fields.subList(1, fields.size())) {
            fallback = optionalText(document, field.key());
            if (!fallback.isBlank()) break;
        }
        return new SearchHit(
                entity.code(),
                Long.toString(id),
                title.isBlank() ? "#" + id : boundedPlaintext(title),
                snippet(hit, fallback),
                entity.targetUrl(id));
    }

    private static long requiredDocumentId(JsonNode document) {
        long typedId = positiveLong(document.get(SearchDocumentSql.RECORD_ID));
        long stringId = positiveLong(document.get("id"));
        if (typedId != stringId) throw TypesenseException.invalidResponse();
        return typedId;
    }

    private static long positiveLong(JsonNode value) {
        if (value == null || (!value.isIntegralNumber() && !value.isTextual())) {
            throw TypesenseException.invalidResponse();
        }
        String text = value.isTextual() ? value.textValue() : value.asText();
        try {
            long parsed = Long.parseLong(text);
            if (parsed <= 0) throw TypesenseException.invalidResponse();
            return parsed;
        } catch (NumberFormatException invalidId) {
            throw TypesenseException.invalidResponse();
        }
    }

    private static long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw TypesenseException.invalidResponse();
        }
        long parsed = value.longValue();
        if (parsed < 0) throw TypesenseException.invalidResponse();
        return parsed;
    }

    private static String optionalText(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw TypesenseException.invalidResponse();
        return value.textValue();
    }

    private static String snippet(JsonNode hit, String fallback) {
        String objectHighlight = highlightedText(hit.get("highlight"));
        String arrayHighlight = highlightsArrayText(hit.get("highlights"));
        String highlighted = objectHighlight == null ? arrayHighlight : objectHighlight;
        return boundedPlaintext(highlighted == null ? fallback : highlighted);
    }

    private static String highlightedText(JsonNode highlight) {
        if (highlight == null || highlight.isNull()) return null;
        if (!highlight.isObject()) throw TypesenseException.invalidResponse();
        String firstText = null;
        var fields = highlight.properties().iterator();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value == null || !value.isObject()) throw TypesenseException.invalidResponse();
            String text = requiredHighlightText(value);
            if (firstText == null) firstText = text;
        }
        return firstText;
    }

    private static String highlightsArrayText(JsonNode highlights) {
        if (highlights == null || highlights.isNull()) return null;
        if (!highlights.isArray()) throw TypesenseException.invalidResponse();
        String firstText = null;
        for (JsonNode value : highlights) {
            if (value == null || !value.isObject()) throw TypesenseException.invalidResponse();
            String text = requiredHighlightText(value);
            if (firstText == null) firstText = text;
        }
        return firstText;
    }

    private static String requiredHighlightText(JsonNode value) {
        JsonNode snippet = value.get("snippet");
        JsonNode matched = value.get("value");
        if (snippet != null && !snippet.isTextual()) throw TypesenseException.invalidResponse();
        if (matched != null && !matched.isTextual()) throw TypesenseException.invalidResponse();
        if (snippet == null && matched == null) throw TypesenseException.invalidResponse();
        return snippet == null ? matched.textValue() : snippet.textValue();
    }

    static String boundedPlaintext(String value) {
        if (value == null || value.isEmpty()) return "";
        String decoded = HtmlUtils.htmlUnescape(value);
        String plaintext = decoded.replaceAll("(?s)<[^>]*>", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .strip();
        int codePoints = plaintext.codePointCount(0, plaintext.length());
        if (codePoints <= MAX_SNIPPET_CODE_POINTS) return plaintext;
        int end = plaintext.offsetByCodePoints(0, MAX_SNIPPET_CODE_POINTS);
        return plaintext.substring(0, end);
    }
}
