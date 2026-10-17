package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateList;
import com.smartup24.cms.instance.studio.api.StudioListDtos.Item;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListSummary;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateList;
import com.smartup24.cms.instance.studio.service.StudioListService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reference lists an administrator keeps without a release (ADR-0041): read by form publishers
 * ({@code studio.lists.view}), kept by holders of {@code studio.lists.manage}. Nothing is deleted: an item that should
 * no longer be picked is deactivated.
 */
@RestController
@RequestMapping("/api/v1/studio/lists")
public class StudioListController {
    private static final String LIST = "/api/v1/studio/lists/{code}";
    private static final String ITEM = "/api/v1/studio/lists/{code}/items/{itemCode}";

    private final StudioListService lists;

    public StudioListController(StudioListService lists) {
        this.lists = lists;
    }

    @Operation(
            summary = "List the reference lists",
            description = "Requires studio.lists.view. The newest first; limit 1 to 100 (default 50, else 422) and the"
                    + " nextCursor of the previous page. Each list gives its item counts and the revision a change of"
                    + " its names takes in If-Match.")
    @GetMapping
    @RequiresPermission(form = "studio.lists", action = "view")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<ListSummary> referenceLists(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return lists.list(limit, cursor);
    }

    @Operation(
            summary = "Read a reference list with its items",
            description = "Requires studio.lists.view. Every item (at most 500), inactive ones too, in their order; the"
                    + " list's revision as ETag. 404 for an unknown list.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "studio.lists", action = "view")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<ListDetail> referenceList(@PathVariable String code) {
        ListDetail detail = lists.get(code);
        return ResponseEntity.ok()
                .eTag(Revisions.etag(detail.list().revision()))
                .body(detail);
    }

    @Operation(
            summary = "Create a reference list",
            description = "Requires studio.lists.manage. The code is permanent; names in ru, uz and en of 1 to 80"
                    + " characters. 409 for a taken code, 422 above 200 lists. Audited.")
    @PostMapping
    @RequiresPermission(form = "studio.lists", action = "manage")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<ListDetail> createReferenceList(@Valid @RequestBody CreateList body) {
        ListDetail detail = lists.create(body);
        ResponseEntity<ListDetail> created = Created.at(LIST, detail.list().code(), detail);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .eTag(Revisions.etag(detail.list().revision()))
                .body(detail);
    }

    @Operation(
            summary = "Rename a reference list",
            description = "Requires studio.lists.manage and If-Match with the list's revision (428 without it, 409 when"
                    + " stale). Audited.")
    @PutMapping("/{code}")
    @RequiresPermission(form = "studio.lists", action = "manage")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<ListDetail> renameReferenceList(
            @PathVariable String code,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody UpdateList body) {
        ListDetail detail = lists.rename(code, ifMatch, body);
        return ResponseEntity.ok()
                .eTag(Revisions.etag(detail.list().revision()))
                .body(detail);
    }

    @Operation(
            summary = "Add an item to a reference list",
            description = "Requires studio.lists.manage. The code is permanent and unique in the list (409 otherwise);"
                    + " names in ru, uz and en; order 1 to 9999. 422 above 500 items. Every form field that refers"
                    + " to the list offers the item at once. Audited.")
    @PostMapping("/{code}/items")
    @RequiresPermission(form = "studio.lists", action = "manage")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<Item> createReferenceListItem(
            @PathVariable String code, @Valid @RequestBody CreateItem body) {
        Item item = lists.addItem(code, body);
        ResponseEntity<Item> created = Created.at(ITEM, new Object[] {code, item.code()}, item);
        return ResponseEntity.status(created.getStatusCode())
                .headers(created.getHeaders())
                .eTag(Revisions.etag(item.revision()))
                .body(item);
    }

    @Operation(
            summary = "Change an item of a reference list",
            description = "Requires studio.lists.manage and If-Match with the item's revision (428/409). Names, order"
                    + " and whether new values may take it; an inactive item keeps its name in the records that hold"
                    + " it. The code never changes. Audited.")
    @PutMapping("/{code}/items/{itemCode}")
    @RequiresPermission(form = "studio.lists", action = "manage")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<Item> updateReferenceListItem(
            @PathVariable String code,
            @PathVariable String itemCode,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody UpdateItem body) {
        Item item = lists.updateItem(code, itemCode, ifMatch, body);
        return ResponseEntity.ok().eTag(Revisions.etag(item.revision())).body(item);
    }
}
