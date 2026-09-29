package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.service.FieldPolicy;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class SearchQueryPolicyTest {

    @Test
    void defaultsExposeTheApprovedTypedFieldPolicy() {
        SearchQueryPolicy policy = SearchQueryPolicy.defaults();

        assertThat(policy.globalLimit()).isEqualTo(10);
        assertThat(policy.requestsPerMinute()).isEqualTo(120);
        assertThat(policy.burst()).isEqualTo(20);
        assertThat(policy.schemaProfile()).isEqualTo("MIXED");
        assertThat(policy.fields())
                .containsExactly(
                        org.assertj.core.api.Assertions.entry(
                                "TASK",
                                List.of(
                                        new FieldPolicy("title", 10, 2, true),
                                        new FieldPolicy("description_markdown", 3, 2, true),
                                        new FieldPolicy("status_name", 2, 2, true),
                                        new FieldPolicy("project_name", 2, 2, true))),
                        org.assertj.core.api.Assertions.entry(
                                "PROJECT",
                                List.of(
                                        new FieldPolicy("name", 10, 2, true),
                                        new FieldPolicy("description", 3, 2, true))),
                        org.assertj.core.api.Assertions.entry(
                                "USER",
                                List.of(
                                        new FieldPolicy("name", 10, 2, true),
                                        new FieldPolicy("login", 8, 0, true),
                                        new FieldPolicy("email", 6, 0, true),
                                        new FieldPolicy("phone", 6, 0, true))));
    }

    @Test
    void policyDefensivelyCopiesNestedCollections() {
        SearchQueryPolicy policy = SearchQueryPolicy.defaults();

        assertThatThrownBy(() -> policy.fields().put("OTHER", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.fields().get("TASK").add(new FieldPolicy("other", 1, 0, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aBrokenRuleIsTheRequestsBadRequestWithItsOwnKey() {
        var fields = SearchQueryPolicy.defaults().fields();
        assertRefused(() -> new SearchQueryPolicy(0, 120, 20, "MIXED", fields), "error.search.global_limit_range");
        assertRefused(() -> new SearchQueryPolicy(10, 120, 200, "MIXED", fields), "error.search.rate_budget_invalid");
        assertRefused(() -> new SearchQueryPolicy(10, 120, 20, "EN", fields), "error.search.schema_profile_invalid");
        assertRefused(
                () -> new SearchQueryPolicy(10, 120, 20, "MIXED", Map.of("TASK", fields.get("TASK"))),
                "error.search.entities_invalid");
        var noWeight = new LinkedHashMap<>(fields);
        noWeight.put(
                "PROJECT", List.of(new FieldPolicy("name", 0, 2, true), new FieldPolicy("description", 0, 2, true)));
        assertThatThrownBy(() -> new SearchQueryPolicy(10, 120, 20, "MIXED", noWeight))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getMessageKey()).isEqualTo("error.search.fields_invalid");
                    assertThat(error.getParams()).containsEntry("entity", "PROJECT");
                });
        assertRefused(() -> new FieldPolicy(" ", 1, 0, true), "error.search.field_required");
        assertRefused(() -> new FieldPolicy("name", 128, 0, true), "error.search.field_weight_range");
        assertRefused(() -> new FieldPolicy("name", 1, 3, true), "error.search.field_typos_range");
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
