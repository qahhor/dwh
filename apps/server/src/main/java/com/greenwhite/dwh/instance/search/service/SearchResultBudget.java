package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.CollectionSearch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Applies a fair cross-category cap without comparing collection-specific relevance scores. */
@Component
public class SearchResultBudget {

    public List<SearchHit> allocate(List<CollectionSearch> groups, int globalLimit) {
        if (groups == null || groups.isEmpty() || globalLimit <= 0) return List.of();

        int[] allocation = new int[groups.size()];
        int allocated = 0;
        boolean progressed;
        do {
            progressed = false;
            for (int i = 0; i < groups.size() && allocated < globalLimit; i++) {
                if (allocation[i] < groups.get(i).hits().size()) {
                    allocation[i]++;
                    allocated++;
                    progressed = true;
                }
            }
        } while (progressed && allocated < globalLimit);

        List<SearchHit> result = new ArrayList<>(allocated);
        for (int i = 0; i < groups.size(); i++) {
            result.addAll(groups.get(i).hits().subList(0, allocation[i]));
        }
        return List.copyOf(result);
    }
}
