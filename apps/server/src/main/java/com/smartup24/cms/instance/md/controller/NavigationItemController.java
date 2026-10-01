package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.NavigationItemView;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.NavigationItemService;
import com.smartup24.cms.instance.md.service.NavigationItemService.CreateNavigationItemCommand;
import com.smartup24.cms.instance.md.service.NavigationItemService.PermissionChoice;
import com.smartup24.cms.instance.md.service.NavigationItemService.UpdateNavigationItemCommand;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/navigation/items")
public class NavigationItemController {

    private final NavigationItemService navigationService;

    public NavigationItemController(NavigationItemService navigationService) {
        this.navigationService = navigationService;
    }

    @Operation(summary = "Get the navigation menu", description = "The active menu items for the caller.")
    @GetMapping("/active")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<NavigationItemView>> getActiveItems() {
        return ResponseEntity.ok(NavigationItemService.visibleToViewer(navigationService.getActiveItems()));
    }

    /** Пары каталога, которыми можно ограничить пункт меню. */
    @Operation(
            summary = "List menu permission choices",
            description = "The catalog pairs a menu item may be restricted by.")
    @GetMapping("/permissions")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    public ResponseEntity<List<PermissionChoice>> getPermissionChoices() {
        return ResponseEntity.ok(navigationService.getPermissionChoices());
    }

    @Operation(summary = "List menu items", description = "Every menu item, visible or hidden, for the menu editor.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "view")
    public ResponseEntity<List<NavigationItemView>> getAllItems() {
        return ResponseEntity.ok(navigationService.getAllItems());
    }

    @Operation(summary = "Get a menu item", description = "One menu item.")
    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "view")
    public ResponseEntity<NavigationItemView> getItem(@PathVariable Long id) {
        return navigationService
                .getItemById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.md.navigation_item_not_found", Map.of("id", id)));
    }

    @Operation(summary = "Get a menu item by code", description = "One menu item found by its code.")
    @GetMapping("/by-code/{code}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<NavigationItemView> getItemByCode(@PathVariable String code) {
        return navigationService
                .getVisibleItemByCode(code)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.md.navigation_item_code_not_found", Map.of("code", code)));
    }

    @Operation(summary = "Create a menu item", description = "Adds an item to the navigation menu.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<NavigationItemView> createItem(@Valid @RequestBody CreateNavigationItemCommand cmd) {
        Long userId = SecurityContext.getCurrentUserId();
        NavigationItemView created = navigationService.createItem(cmd, userId);
        return Created.at("/api/v1/navigation/items/{id}", created.id(), created);
    }

    @Operation(summary = "Update a menu item", description = "Replaces a menu item; names the revision it was read at.")
    @PutMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    public ResponseEntity<NavigationItemView> updateItem(
            @PathVariable Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateNavigationItemCommand cmd) {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(navigationService.updateItem(id, cmd, userId, Revisions.required(ifMatch)));
    }

    public record ActiveRequest(boolean active) {}

    /** Shows or hides the item (plan item 3.4): the body states the result, so a repeat changes nothing. */
    @Operation(
            summary = "Show or hide a menu item",
            description = "Sets whether a menu item is shown; the body states the result, so a repeat changes nothing.")
    @PutMapping("/{id}/active")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    public ResponseEntity<NavigationItemView> setActive(@PathVariable Long id, @RequestBody ActiveRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(navigationService.setActive(id, userId, body.active()));
    }

    /** Flips the state: deprecated for PUT /{id}/active, answers until its sunset (ADR-0023). */
    @Operation(
            summary = "Toggle a menu item (deprecated)",
            description =
                    "Flips whether a menu item is shown. Deprecated for PUT /api/v1/navigation/items/{id}/active; answers until its sunset.")
    @PostMapping("/{id}/toggle")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    public ResponseEntity<NavigationItemView> toggleItem(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(navigationService.toggleState(id, userId));
    }

    @Operation(summary = "Delete a menu item", description = "Removes a menu item.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_NAVIGATION, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        navigationService.deleteItem(id, userId);
        return ResponseEntity.noContent().build();
    }
}
