package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable search behavior policy; never exposes raw Typesense request parameters. The field weights are kept per
 * entity the search indexes, by its code (ADR-0032, 10.3): an entity the policy does not name searches its fields with
 * the defaults of {@link SearchFieldPolicies}, and a save names only entities and fields the search knows.
 */
public record SearchQueryPolicy(
        int globalLimit,
        int requestsPerMinute,
        int burst,
        String schemaProfile,
        Map<String, List<FieldPolicy>> fields) {

    /** The code of an entity, the key of its fields. */
    private static final Pattern ENTITY = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

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
                || fields.keySet().stream()
                        .anyMatch(key -> key == null || !ENTITY.matcher(key).matches()))
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.entities_invalid");
        var copy = new TreeMap<String, List<FieldPolicy>>();
        fields.forEach((entityType, policies) -> {
            if (policies == null
                    || policies.isEmpty()
                    || policies.stream().anyMatch(Objects::isNull)
                    || policies.stream()
                                    .map(FieldPolicy::field)
                                    .collect(Collectors.toSet())
                                    .size()
                            != policies.size()
                    || policies.stream().noneMatch(field -> field.weight() > 0))
                throw ApiException.badRequest(
                        ErrorCode.BAD_REQUEST, "error.search.fields_invalid", Map.of("entity", entityType));
            copy.put(entityType, List.copyOf(policies));
        });
        fields = Collections.unmodifiableMap(copy);
    }

    /** The policy of a new installation: the default limits, every entity with the default weights. */
    public static SearchQueryPolicy defaults() {
        return new SearchQueryPolicy(10, 120, 20, "MIXED", Map.of());
    }

    /** The same policy with these field weights. */
    public SearchQueryPolicy withFields(Map<String, List<FieldPolicy>> entityFields) {
        return new SearchQueryPolicy(globalLimit, requestsPerMinute, burst, schemaProfile, entityFields);
    }
}
