package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.SearchResultBudget;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.CollectionSearch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultBudgetTest {

    private final SearchResultBudget budget = new SearchResultBudget();

    @Test
    void allocatesFairlyBeforeRestoringGroupOrder() {
        List<SearchHit> hits = budget.allocate(List.of(
                group("TASK", "11", "12", "13"),
                group("PROJECT", "21", "22"),
                group("USER", "31")), 4);

        assertThat(hits).extracting(SearchHit::id).containsExactly("11", "12", "21", "31");
    }

    @Test
    void emptyGroupsDoNotWasteTheGlobalBudget() {
        List<SearchHit> hits = budget.allocate(List.of(
                group("TASK"),
                group("PROJECT", "21", "22", "23"),
                group("USER", "31", "32")), 4);

        assertThat(hits).extracting(SearchHit::id).containsExactly("21", "22", "31", "32");
    }

    private static CollectionSearch group(String entityType, String... ids) {
        List<SearchHit> hits = java.util.Arrays.stream(ids)
                .map(id -> new SearchHit(entityType, id, entityType + " " + id, "", "/" + id))
                .toList();
        return new CollectionSearch(entityType, hits, hits.size(), 1);
    }
}
