package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.NavigationItemView;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository.FormTreeItem;
import com.smartup24.cms.instance.md.repository.NavigationItemRepository;
import com.smartup24.cms.instance.md.repository.NavigationItemRepository.NavigationItemRecord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NavigationItemService {

    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^(https?://|/).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_SCHEME =
            Pattern.compile("^(javascript|data|vbscript):.*", Pattern.CASE_INSENSITIVE);

    /** The item's permission is given as an unknown or obsolete catalog pair. */
    public static final String PERMISSION_UNKNOWN = "NAVIGATION_PERMISSION_UNKNOWN";

    private final NavigationItemRepository navigationRepository;
    private final AuditLogService auditLogService;
    private final MdPermissionService permissionService;

    public NavigationItemService(
            NavigationItemRepository navigationRepository,
            AuditLogService auditLogService,
            MdPermissionService permissionService) {
        this.navigationRepository = navigationRepository;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
    }

    /** A catalog pair that can be assigned to a menu item: {@code form.action} and its names. */
    public record PermissionChoice(String permission, String formName, String actionName) {}

    public record CreateNavigationItemCommand(
            String code,
            String title,
            String titleKey,
            String sectionId,
            Long parentId,
            String icon,
            String targetType,
            String url,
            boolean openInIframe,
            String requiredPermission,
            int sortOrder) {}

    public record UpdateNavigationItemCommand(
            String code,
            String title,
            String titleKey,
            String sectionId,
            Long parentId,
            String icon,
            String targetType,
            String url,
            boolean openInIframe,
            String requiredPermission,
            int sortOrder,
            String state) {}

    @Transactional(readOnly = true)
    @Cacheable(value = "navigationItems", key = "'all'")
    public List<NavigationItemView> getAllItems() {
        return navigationRepository.findAll().stream()
                .map(NavigationItemService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "navigationItems", key = "'active'")
    public List<NavigationItemView> getActiveItems() {
        return navigationRepository.findActive().stream()
                .map(NavigationItemService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<NavigationItemView> getItemById(Long id) {
        return navigationRepository.findById(id).map(NavigationItemService::view);
    }

    @Transactional(readOnly = true)
    public Optional<NavigationItemView> getItemByCode(String code) {
        return navigationRepository.findByCode(code).map(NavigationItemService::view);
    }

    /**
     * Items the current user sees (FR-MOD-02): an item with a permission
     * is visible only to holders of that permission, and a nested item only together
     * with its parent. The filter is applied to the shared cache of active items, so it
     * is called after {@link #getActiveItems()}, not inside it.
     */
    public static List<NavigationItemView> visibleToViewer(List<NavigationItemView> items) {
        Map<Long, NavigationItemView> byId = new HashMap<>();
        items.forEach(item -> byId.put(item.id(), item));
        return items.stream()
                .filter(item -> visible(item, byId, new HashSet<>()))
                .toList();
    }

    /** An item by code for an embedded report: a forbidden item looks the same as a missing one. */
    @Transactional(readOnly = true)
    public Optional<NavigationItemView> getVisibleItemByCode(String code) {
        return getItemByCode(code)
                .filter(item -> "A".equals(item.state()))
                .filter(item ->
                        visibleToViewer(getAllItemsUncached()).stream().anyMatch(v -> v.id().equals(item.id())));
    }

    /** Live catalog pairs for choosing a permission in the menu settings. */
    @Transactional(readOnly = true)
    public List<PermissionChoice> getPermissionChoices() {
        return permissionService.getFormCatalog().stream()
                .filter(item -> !item.isDeprecated())
                .map(NavigationItemService::choice)
                .toList();
    }

    private static PermissionChoice choice(FormTreeItem item) {
        return new PermissionChoice(item.formCode() + "." + item.action(), item.formName(), item.actionName());
    }

    private List<NavigationItemView> getAllItemsUncached() {
        return navigationRepository.findAll().stream()
                .map(NavigationItemService::view)
                .toList();
    }

    private static boolean visible(NavigationItemView item, Map<Long, NavigationItemView> byId, Set<Long> seen) {
        if (!seen.add(item.id()) || !"A".equals(item.state()) || !allowed(item.requiredPermission())) {
            return false;
        }
        if (item.parentId() == null) {
            return true;
        }
        NavigationItemView parent = byId.get(item.parentId());
        return parent != null && visible(parent, byId, seen);
    }

    private static boolean allowed(String permission) {
        if (permission == null) {
            return true;
        }
        int dot = permission.lastIndexOf('.');
        return dot > 0
                && dot < permission.length() - 1
                && SecurityContext.hasPermission(permission.substring(0, dot), permission.substring(dot + 1));
    }

    private String requiredPermission(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // A legacy form code is still accepted until the sunset of ADR-0028's mapping (2026-12-31).
        String permission = PermissionAreas.currentPermission(value.trim());
        if (!permissionService.getGrantablePairs().contains(permission)) {
            throw ApiException.validation(
                    "error.md.navigation_permission_unknown",
                    Map.of("permission", permission),
                    List.of(FieldErrorItem.keyed(
                            "requiredPermission",
                            PERMISSION_UNKNOWN,
                            "error.md.navigation_permission_unknown",
                            Map.of("permission", permission))));
        }
        return permission;
    }

    @Transactional
    @CacheEvict(value = "navigationItems", allEntries = true)
    public NavigationItemView createItem(CreateNavigationItemCommand cmd, Long userId) {
        validateUrl(cmd.url());
        String code = normalizeCode(cmd.code());
        if (code.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.navigation_code_required");
        }

        if (navigationRepository.findByCode(code).isPresent()) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.navigation_code_exists", Map.of("code", code));
        }

        NavigationItemRecord record = new NavigationItemRecord(
                null,
                code,
                cmd.title().trim(),
                trimmedOr(cmd.titleKey(), null),
                trimmedOr(cmd.sectionId(), "custom"),
                cmd.parentId(),
                trimmedOr(cmd.icon(), "bar_chart"),
                trimmedOr(cmd.targetType(), "EMBEDDED_IFRAME"),
                cmd.url().trim(),
                cmd.openInIframe(),
                requiredPermission(cmd.requiredPermission()),
                cmd.sortOrder(),
                "A",
                userId,
                userId,
                null,
                null,
                1L);

        Long id = navigationRepository.insert(record);
        auditLogService.logChange(
                "md_navigation_items",
                String.valueOf(id),
                "I",
                List.of("code", "title", "url", "target_type", "required_permission"),
                null,
                Map.of(
                        "code",
                        code,
                        "title",
                        record.title(),
                        "url",
                        record.url(),
                        "target_type",
                        record.targetType(),
                        "required_permission",
                        Objects.toString(record.requiredPermission(), "")));

        return getItemById(id).orElseThrow();
    }

    @Transactional
    @CacheEvict(value = "navigationItems", allEntries = true)
    public NavigationItemView updateItem(Long id, UpdateNavigationItemCommand cmd, Long userId, long expectedRevision) {
        NavigationItemRecord existing = navigationRepository.findById(id).orElseThrow(() -> itemNotFound(id));

        validateUrl(cmd.url());
        String code = normalizeCode(cmd.code());

        Optional<NavigationItemRecord> withSameCode = navigationRepository.findByCode(code);
        if (withSameCode.isPresent() && !withSameCode.get().id().equals(id)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.navigation_code_exists", Map.of("code", code));
        }

        NavigationItemRecord updated = new NavigationItemRecord(
                id,
                code,
                cmd.title().trim(),
                trimmedOr(cmd.titleKey(), null),
                trimmedOr(cmd.sectionId(), "custom"),
                cmd.parentId(),
                trimmedOr(cmd.icon(), "bar_chart"),
                trimmedOr(cmd.targetType(), "EMBEDDED_IFRAME"),
                cmd.url().trim(),
                cmd.openInIframe(),
                requiredPermission(cmd.requiredPermission()),
                cmd.sortOrder(),
                cmd.state() != null ? cmd.state() : existing.state(),
                existing.createdBy(),
                userId,
                existing.createdAt(),
                null,
                existing.revision());

        navigationRepository.update(id, updated, expectedRevision);
        auditLogService.logChange(
                "md_navigation_items",
                String.valueOf(id),
                "U",
                List.of("code", "title", "url", "target_type", "state", "required_permission"),
                auditState(existing),
                auditState(updated));

        return getItemById(id).orElseThrow();
    }

    private static String normalizeCode(String code) {
        return code.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    }

    private static String trimmedOr(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    /** The audited columns of an update, as the journal shows them before and after. */
    private static Map<String, Object> auditState(NavigationItemRecord item) {
        return Map.of(
                "code",
                item.code(),
                "title",
                item.title(),
                "url",
                item.url(),
                "target_type",
                item.targetType(),
                "state",
                item.state(),
                "required_permission",
                Objects.toString(item.requiredPermission(), ""));
    }

    /** Sets the state (PUT …/active, plan item 3.4): repeating the call changes nothing and audits nothing. */
    @Transactional
    @CacheEvict(value = "navigationItems", allEntries = true)
    public NavigationItemView setActive(Long id, Long userId, boolean active) {
        if (navigationRepository.findById(id).isEmpty()) throw itemNotFound(id);
        String newState = active ? "A" : "P";
        // One conditional write (plan item 3.6): only a request that changed the state is audited.
        if (navigationRepository.updateState(id, newState, userId) == 1) {
            auditLogService.logChange(
                    "md_navigation_items",
                    String.valueOf(id),
                    "U",
                    List.of("state"),
                    Map.of("state", active ? "P" : "A"),
                    Map.of("state", newState));
        }
        return getItemById(id).orElseThrow();
    }

    @Transactional
    @CacheEvict(value = "navigationItems", allEntries = true)
    public void deleteItem(Long id, Long userId) {
        NavigationItemRecord existing = navigationRepository.findById(id).orElseThrow(() -> itemNotFound(id));

        navigationRepository.delete(id);
        auditLogService.logChange(
                "md_navigation_items", String.valueOf(id), "D", List.of("code"), Map.of("code", existing.code()), null);
    }

    private static ApiException itemNotFound(Long id) {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.navigation_item_not_found", Map.of("id", id));
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.navigation_url_required");
        }
        String trimmed = url.trim();
        if (DANGEROUS_SCHEME.matcher(trimmed).matches()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.navigation_url_scheme_forbidden");
        }
        if (!SAFE_URL_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.navigation_url_invalid");
        }
    }

    static NavigationItemView view(NavigationItemRecord r) {
        return new NavigationItemView(
                r.id(),
                r.code(),
                r.title(),
                r.titleKey(),
                r.sectionId(),
                r.parentId(),
                r.icon(),
                r.targetType(),
                r.url(),
                r.openInIframe(),
                r.requiredPermission(),
                r.sortOrder(),
                r.state(),
                r.createdBy(),
                r.modifiedBy(),
                r.createdAt(),
                r.modifiedAt(),
                r.revision());
    }
}
