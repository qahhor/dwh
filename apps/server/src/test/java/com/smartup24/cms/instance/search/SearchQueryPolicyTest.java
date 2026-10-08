package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.service.FieldPolicy;
import com.smartup24.cms.instance.search.service.SearchFieldPolicies;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The search policy (ADR-0032, 10.3): the limits, the schema profile and the field weights per entity code. An entity
 * the policy does not name searches its spec's fields with the defaults; a save names only known entities and exactly
 * their fields.
 */
class SearchQueryPolicyTest {

    private final SearchFieldPolicies policies = new SearchFieldPolicies(SearchTestEntities.unscoped());

    @Test
    void defaultsExposeTheApprovedLimitsAndEveryEntityWithItsSpecsFields() {
        SearchQueryPolicy policy = SearchQueryPolicy.defaults();

        assertThat(policy.globalLimit()).isEqualTo(10);
        assertThat(policy.requestsPerMinute()).isEqualTo(120);
        assertThat(policy.burst()).isEqualTo(20);
        assertThat(policy.schemaProfile()).isEqualTo("MIXED");
        assertThat(policy.fields()).isEmpty();
        var effective = policies.effective(policy).fields();
        assertThat(effective.keySet())
                .containsExactly("example.orders", "md.users", "ms.notes", "ms.projects", "ms.tasks");
        assertThat(effective.get("ms.tasks"))
                .containsExactly(
                        new FieldPolicy("title", 10, 2, true), new FieldPolicy("descriptionMarkdown", 3, 2, true));
        assertThat(effective.get("md.users"))
                .containsExactly(
                        new FieldPolicy("name", 10, 2, true),
                        new FieldPolicy("login", 3, 2, true),
                        new FieldPolicy("email", 3, 0, true),
                        new FieldPolicy("phone", 3, 0, true));
    }

    @Test
    void savedWeightsApplyOnlyWhileTheyNameExactlyTheSpecsFields() {
        var saved = List.of(new FieldPolicy("title", 5, 1, false), new FieldPolicy("descriptionMarkdown", 0, 2, true));
        var policy = new SearchQueryPolicy(10, 120, 20, "MIXED", Map.of("ms.tasks", saved));
        var tasks = SearchTestEntities.entity(SearchTestEntities.TASKS);
        assertThat(policies.of(policy, tasks)).isEqualTo(saved);
        var stale = new SearchQueryPolicy(
                10, 120, 20, "MIXED", Map.of("ms.tasks", List.of(new FieldPolicy("title", 5, 1, false))));
        assertThat(policies.of(stale, tasks)).isEqualTo(SearchFieldPolicies.defaults(tasks));
    }

    @Test
    void policyDefensivelyCopiesNestedCollections() {
        SearchQueryPolicy policy = policies.effective(SearchQueryPolicy.defaults());

        assertThatThrownBy(() -> policy.fields().put("other.entity", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.fields().get("ms.tasks").add(new FieldPolicy("other", 1, 0, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aBrokenRuleIsTheRequestsBadRequestWithItsOwnKey() {
        var fields = policies.effective(SearchQueryPolicy.defaults()).fields();
        assertRefused(() -> new SearchQueryPolicy(0, 120, 20, "MIXED", fields), "error.search.global_limit_range");
        assertRefused(() -> new SearchQueryPolicy(10, 120, 200, "MIXED", fields), "error.search.rate_budget_invalid");
        assertRefused(() -> new SearchQueryPolicy(10, 120, 20, "EN", fields), "error.search.schema_profile_invalid");
        assertRefused(
                () -> new SearchQueryPolicy(10, 120, 20, "MIXED", Map.of("TASK", fields.get("ms.tasks"))),
                "error.search.entities_invalid");
        var noWeight = new LinkedHashMap<>(fields);
        noWeight.put(
                "ms.projects",
                List.of(new FieldPolicy("name", 0, 2, true), new FieldPolicy("description", 0, 2, true)));
        assertThatThrownBy(() -> new SearchQueryPolicy(10, 120, 20, "MIXED", noWeight))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getMessageKey()).isEqualTo("error.search.fields_invalid");
                    assertThat(error.getParams()).containsEntry("entity", "ms.projects");
                });
        assertRefused(() -> new FieldPolicy(" ", 1, 0, true), "error.search.field_required");
        assertRefused(() -> new FieldPolicy("name", 128, 0, true), "error.search.field_weight_range");
        assertRefused(() -> new FieldPolicy("name", 1, 3, true), "error.search.field_typos_range");
    }

    @Test
    void aSaveNamesOnlyKnownEntitiesAndExactlyTheirSpecsFields() {
        assertRefused(
                () -> policies.requireKnown(new SearchQueryPolicy(
                        10, 120, 20, "MIXED", Map.of("no.such", List.of(new FieldPolicy("title", 1, 0, true))))),
                "error.search.entities_invalid");
        assertRefused(
                () -> policies.requireKnown(new SearchQueryPolicy(
                        10, 120, 20, "MIXED", Map.of("ms.tasks", List.of(new FieldPolicy("secret", 1, 0, true))))),
                "error.search.fields_invalid");
        policies.requireKnown(policies.effective(SearchQueryPolicy.defaults()));
    }

    @Test
    void aStoredPolicyThatBreaksARuleIsUnavailableConfigurationNotABadRequest() {
        String stored = SearchManagementDtos.encodePolicy(SearchQueryPolicy.defaults())
                .replace("\"globalLimit\":10", "\"globalLimit\":0");
        assertThatThrownBy(() -> SearchManagementDtos.decodeStored(stored, 5))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessageKey()).isEqualTo("error.search.config_invalid");
                });
    }

    private static void assertRefused(ThrowingCallable call, String key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(error.getMessageKey()).isEqualTo(key);
        });
    }
}
