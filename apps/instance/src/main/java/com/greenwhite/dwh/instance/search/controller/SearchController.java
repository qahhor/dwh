package com.greenwhite.dwh.instance.search.controller;

import com.greenwhite.dwh.instance.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.search.pref.SearchPref;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @RequiresPermission(form = SearchPref.FORM_SEARCH, action = "view")
    public ResponseEntity<SearchService.SearchResult> search(
            @RequestParam("q") String query,
            @RequestParam(name = "entity", required = false) String entityType,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {

        return ResponseEntity.ok(searchService.search(query, entityType, limit));
    }
}
