package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable search behavior policy; never exposes raw Typesense request parameters. */
public record SearchQueryPolicy(
        int globalLimit,
        int requestsPerMinute,
        int burst,
        String schemaProfile,
        Map<String, List<FieldPolicy>> fields) {

    // A settings save or preview request builds this record: a broken rule is the administrator's 400, not a 500.
    // A stored policy that fails the same rules is turned into a 503 by SearchManagementDtos.decodeStored.
    public SearchQueryPolicy {
        if (globalLimit < 1 || globalLimit > 50)
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.global_limit_range");
        if (requestsPerMinute < 30 || requestsPerMinute > 600 || burst < 10 || burst > 60 || burst > requestsPerMinute)
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.rate_budget_invalid");
        if (!"MIXED".equals(schemaProfile) && !"RU".equals(schemaProfile))
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.schema_profile_invalid");
        if (fields == null
                || !fields.keySet().containsAll(Set.of("TASK", "PROJECT", "USER"))
                || !Set.of("TASK", "PROJECT", "USER", "NOTE").containsAll(fields.keySet()))
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.entities_invalid");
        var copy = new LinkedHashMap<String, List<FieldPolicy>>();
        fields.forEach((entityType, policies) -> {
            Set<String> permitted = switch (entityType) {
                case "TASK" -> Set.of("title", "description_markdown", "status_name", "project_name");
                case "PROJECT" -> Set.of("name", "description");
                case "USER" -> Set.of("name", "login", "email", "phone");
                case "NOTE" -> Set.of("title", "content_md", "color");
                default -> throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.entities_invalid");
            };
            if (policies == null
                    || policies.size() != permitted.size()
                    || policies.stream().anyMatch(Objects::isNull)
                    || !policies.stream()
                            .map(FieldPolicy::field)
                            .collect(Collectors.toSet())
                            .equals(permitted)
                    || policies.stream().noneMatch(field -> field.weight() > 0))
                throw ApiException.badRequest(
                        ErrorCode.BAD_REQUEST, "error.search.fields_invalid", Map.of("entity", entityType));
            copy.put(entityType, List.copyOf(policies));
        });
        fields = Collections.unmodifiableMap(copy);
    }

    public static SearchQueryPolicy defaults() {
        var fields = new LinkedHashMap<String, List<FieldPolicy>>();
        fields.put(
                "TASK",
                List.of(
                        new FieldPolicy("title", 10, 2, true),
                        new FieldPolicy("description_markdown", 3, 2, true),
                        new FieldPolicy("status_name", 2, 2, true),
                        new FieldPolicy("project_name", 2, 2, true)));
        fields.put(
                "PROJECT", List.of(new FieldPolicy("name", 10, 2, true), new FieldPolicy("description", 3, 2, true)));
        fields.put(
                "USER",
                List.of(
                        new FieldPolicy("name", 10, 2, true),
                        new FieldPolicy("login", 8, 0, true),
                        new FieldPolicy("email", 6, 0, true),
                        new FieldPolicy("phone", 6, 0, true)));
        return new SearchQueryPolicy(10, 120, 20, "MIXED", fields);
    }
}
