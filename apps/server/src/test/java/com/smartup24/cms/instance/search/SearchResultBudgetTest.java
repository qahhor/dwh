package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchResultBudgetTest {

    private final SearchResultBudget budget = new SearchResultBudget();

    @Test
    void allocatesFairlyBeforeRestoringGroupOrder() {
        List<SearchHit> hits = budget.allocate(
                List.of(group("ms.tasks", "11", "12", "13"), group("ms.projects", "21", "22"), group("md.users", "31")),
                4);

        assertThat(hits).extracting(SearchHit::id).containsExactly("11", "12", "21", "31");
    }

    @Test
    void emptyGroupsDoNotWasteTheGlobalBudget() {
        List<SearchHit> hits = budget.allocate(
                List.of(group("ms.tasks"), group("ms.projects", "21", "22", "23"), group("md.users", "31", "32")), 4);

        assertThat(hits).extracting(SearchHit::id).containsExactly("21", "22", "31", "32");
    }

    private static CollectionSearch group(String entityType, String... ids) {
        List<SearchHit> hits = Arrays.stream(ids)
                .map(id -> new SearchHit(entityType, id, entityType + " " + id, "", "/" + id))
                .toList();
        return new CollectionSearch(entityType, hits, hits.size(), 1);
    }
}
