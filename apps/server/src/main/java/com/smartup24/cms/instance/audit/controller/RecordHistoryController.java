package com.smartup24.cms.instance.audit.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.RecordHistoryService;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(
            summary = "List record kinds with history",
            description = "The kinds of records whose change history can be read.")
    @GetMapping
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<List<String>> kinds() {
        return ResponseEntity.ok(historyService.availableKinds());
    }

    @Operation(
            summary = "Get the history of a record",
            description = "The recorded changes of one record, a keyset page at a time.")
    @GetMapping("/{kind}/{id}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<KeysetPage<RecordHistoryService.HistoryEntry>> history(
            @PathVariable("kind") String kind,
            @PathVariable("id") String id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(historyService.history(kind, id, limit, cursor));
    }
}
