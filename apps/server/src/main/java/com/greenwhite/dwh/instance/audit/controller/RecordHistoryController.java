package com.greenwhite.dwh.instance.audit.controller;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.service.RecordHistoryService;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The history tab of a record card (ADR-0017). Any signed-in person may ask;
 * the service then requires the record's own right and data scope, as the
 * field registry's query-meta does for lists.
 */
@RestController
@RequestMapping("/api/v1/history")
public class RecordHistoryController {

    private final RecordHistoryService historyService;

    public RecordHistoryController(RecordHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<List<String>> kinds() {
        return ResponseEntity.ok(historyService.availableKinds());
    }

    @GetMapping("/{kind}/{id}")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<KeysetPage<RecordHistoryService.HistoryEntry>> history(
            @PathVariable("kind") String kind,
            @PathVariable("id") String id,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(historyService.history(kind, id, limit, cursor));
    }
}
