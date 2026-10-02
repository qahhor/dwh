package com.smartup24.cms.instance.search.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.search.pref.SearchPref;
import com.smartup24.cms.instance.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.ResponseEntity;
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

    @Operation(
            summary = "Search",
            description = "Full-text search over the records the caller may see: the entities with the SEARCH"
                    + " capability, in the caller's scope; entity is ALL or the code of one entity.")
    @GetMapping
    @RequiresPermission(form = SearchPref.FORM_SEARCH, action = "view")
    public ResponseEntity<SearchService.SearchResult> search(
            @RequestParam("q") String query,
            @RequestParam(name = "entity", required = false) String entityType,
            @RequestParam(name = "limit", required = false) Integer limit,
            jakarta.servlet.http.HttpServletRequest request) {

        if (limit == null && request.getParameter("limit") != null) {
            // Preserve the service's authorization-before-validation order for an empty integer.
            limit = 0;
        }
        return ResponseEntity.ok(searchService.search(query, entityType, limit));
    }

    @Operation(
            summary = "List search categories",
            description = "The entities the caller may search, each with the label key and icon of its menu item.")
    @GetMapping("/entities")
    @RequiresPermission(form = SearchPref.FORM_SEARCH, action = "view")
    public List<SearchService.SearchCategory> categories() {
        return searchService.categories();
    }
}
