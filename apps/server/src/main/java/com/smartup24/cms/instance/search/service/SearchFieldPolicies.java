package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The field weights of each entity the search indexes (ADR-0032, 10.3): the ones the saved policy names for it, when they
 * name exactly the fields of its search spec, otherwise the defaults — the title weighs most, an address or a phone
 * number is matched without typos. A save names only entities that declare the search and, for each, exactly the fields
 * of its spec.
 */
@Component
public class SearchFieldPolicies {

    private static final int TITLE_WEIGHT = 10;
    private static final int BODY_WEIGHT = 3;
    private static final int TYPOS = 2;

    private final SearchEntities entities;

    public SearchFieldPolicies(SearchEntities entities) {
        this.entities = entities;
    }

    /** The weights the entity is searched with under the policy. */
    public List<FieldPolicy> of(SearchQueryPolicy policy, SearchEntity entity) {
        List<FieldPolicy> saved = policy.fields().get(entity.code());
        if (saved != null
                && names(saved).equals(new LinkedHashSet<>(entity.spec().fields()))) return saved;
        return defaults(entity);
    }

    /** The default weights of the entity's fields: the title {@value #TITLE_WEIGHT}, the others {@value #BODY_WEIGHT}. */
    public static List<FieldPolicy> defaults(SearchEntity entity) {
        List<FieldPolicy> fields = new ArrayList<>();
        boolean title = true;
        for (EntityField field : entity.fields()) {
            boolean exact = field.type() == FieldType.EMAIL || field.type() == FieldType.PHONE;
            fields.add(new FieldPolicy(field.key(), title ? TITLE_WEIGHT : BODY_WEIGHT, exact ? 0 : TYPOS, true));
            title = false;
        }
        return List.copyOf(fields);
    }

    /** The policy with the weights of every entity the search indexes, as the settings screen edits them. */
    public SearchQueryPolicy effective(SearchQueryPolicy policy) {
        Map<String, List<FieldPolicy>> fields = new LinkedHashMap<>();
        for (SearchEntity entity : entities.all()) {
            fields.put(entity.code(), of(policy, entity));
        }
        return policy.withFields(fields);
    }

    /**
     * Refuses a policy that names an entity the search does not index (400 {@code error.search.entities_invalid}) or,
     * for an entity, other fields than its spec's ({@code error.search.fields_invalid}).
     */
    public void requireKnown(SearchQueryPolicy policy) {
        policy.fields().forEach((code, fields) -> {
            SearchEntity entity = entities.find(code)
                    .orElseThrow(() -> ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.entities_invalid"));
            if (!names(fields).equals(new LinkedHashSet<>(entity.spec().fields()))) {
                throw ApiException.badRequest(
                        ErrorCode.BAD_REQUEST, "error.search.fields_invalid", Map.of("entity", code));
            }
        });
    }

    private static Set<String> names(List<FieldPolicy> fields) {
        return fields.stream().map(FieldPolicy::field).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
