package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.FieldPolicy;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryPolicyTest {

    @Test
    void defaultsExposeTheApprovedTypedFieldPolicy() {
        SearchQueryPolicy policy = SearchQueryPolicy.defaults();

        assertThat(policy.globalLimit()).isEqualTo(10);
        assertThat(policy.requestsPerMinute()).isEqualTo(120);
        assertThat(policy.burst()).isEqualTo(20);
        assertThat(policy.schemaProfile()).isEqualTo("MIXED");
        assertThat(policy.fields()).containsExactly(
                org.assertj.core.api.Assertions.entry("TASK", List.of(
                        new FieldPolicy("title", 10, 2, true),
                        new FieldPolicy("description_markdown", 3, 2, true),
                        new FieldPolicy("status_name", 2, 2, true),
                        new FieldPolicy("project_name", 2, 2, true))),
                org.assertj.core.api.Assertions.entry("PROJECT", List.of(
                        new FieldPolicy("name", 10, 2, true),
                        new FieldPolicy("description", 3, 2, true))),
                org.assertj.core.api.Assertions.entry("USER", List.of(
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
}
