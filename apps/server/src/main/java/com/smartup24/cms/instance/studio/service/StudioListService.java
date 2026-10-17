package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioListDtos;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.CreateList;
import com.smartup24.cms.instance.studio.api.StudioListDtos.Item;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListSummary;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateItem;
import com.smartup24.cms.instance.studio.api.StudioListDtos.UpdateList;
import com.smartup24.cms.instance.studio.repository.StudioListRepository;
import com.smartup24.cms.instance.studio.repository.StudioListRepository.ListRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The reference lists an administrator keeps (ADR-0041). A list and an item are never deleted and keep their codes:
 * records hold the codes. An item that should no longer be picked is deactivated — the records keep it and still show
 * its name. Every change names the revision it was made from ({@code If-Match}), is audited and clears the cached items
 * of the list on every node.
 */
@Service
public class StudioListService {
    /** Lists of an installation. */
    public static final int MAX_LISTS = 200;

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private static final Pattern CODE = Pattern.compile(StudioListDtos.CODE);
    private static final Pattern ITEM_CODE = Pattern.compile(StudioListDtos.ITEM_CODE);
    private static final String LISTS = "studio_lists";
    private static final String ITEMS = "studio_list_items";

    private final StudioListRepository lists;
    private final AuditLogService audit;
    private final EntityEnums enums;

    public StudioListService(StudioListRepository lists, AuditLogService audit, EntityEnums enums) {
        this.lists = lists;
        this.audit = audit;
        this.enums = enums;
    }

    @Transactional(readOnly = true)
    public KeysetPage<ListSummary> list(@Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, DEFAULT_LIMIT, MAX_LIMIT);
        return page.page(lists.page(page), row -> new TimePage.Position(row.createdAt(), row.id()))
                .map(StudioListService::summary);
    }

    @Transactional(readOnly = true)
    public ListDetail get(String code) {
        ListRow row = row(code);
        return new ListDetail(summary(row), lists.items(row.id()));
    }

    /** Whether the list {@code code} exists: a live form field may refer to it. */
    @Transactional(readOnly = true)
    public boolean exists(String code) {
        return CODE.matcher(code).matches() && lists.find(code).isPresent();
    }

    @Transactional
    public ListDetail create(CreateList request) {
        requireNames(request.name());
        lists.lock();
        if (lists.find(request.code()).isPresent())
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.studio.list_exists", Map.of("code", request.code()));
        if (lists.count() >= MAX_LISTS)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.list_quota", Map.of("max", MAX_LISTS));
        long id = lists.insert(request.code(), request.name(), SecurityContext.getCurrentUserId());
        audit.logChange(
                LISTS,
                Long.toString(id),
                "I",
                List.of("code", "name"),
                Map.of(),
                Map.of("code", request.code(), "name", names(request.name())));
        return get(request.code());
    }

    @Transactional
    public ListDetail rename(String code, @Nullable String ifMatch, UpdateList request) {
        long revision = Revisions.required(ifMatch);
        requireNames(request.name());
        ListRow row = row(code);
        if (!lists.rename(row.id(), revision, request.name(), SecurityContext.getCurrentUserId()))
            throw Revisions.conflict();
        audit.logChange(
                LISTS,
                Long.toString(row.id()),
                "U",
                List.of("name"),
                Map.of("code", code, "name", names(row.name())),
                Map.of("code", code, "name", names(request.name())));
        return get(code);
    }

    @Transactional
    public Item addItem(String code, CreateItem request) {
        requireNames(request.name());
        lists.lock();
        ListRow row = row(code);
        if (lists.item(row.id(), request.code()).isPresent())
            throw ApiException.conflict(
                    ErrorCode.CONFLICT, "error.studio.list_item_exists", Map.of("code", request.code()));
        if (row.items() >= StudioListDtos.MAX_ITEMS)
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.studio.list_item_quota",
                    Map.of("max", StudioListDtos.MAX_ITEMS));
        lists.insertItem(row.id(), request, SecurityContext.getCurrentUserId());
        audit.logChange(
                ITEMS,
                row.id() + ":" + request.code(),
                "I",
                List.of("code", "name", "sort_order", "active"),
                Map.of(),
                Map.of(
                        "list", code,
                        "code", request.code(),
                        "name", names(request.name()),
                        "sort_order", request.sortOrder(),
                        "active", true));
        changed(code);
        return lists.item(row.id(), request.code()).orElseThrow();
    }

    @Transactional
    public Item updateItem(String code, String itemCode, @Nullable String ifMatch, UpdateItem request) {
        long revision = Revisions.required(ifMatch);
        requireNames(request.name());
        ListRow row = row(code);
        Item before = item(row, itemCode);
        if (!lists.updateItem(row.id(), itemCode, revision, request, SecurityContext.getCurrentUserId()))
            throw Revisions.conflict();
        audit.logChange(
                ITEMS,
                row.id() + ":" + itemCode,
                "U",
                List.of("name", "sort_order", "active"),
                Map.of(
                        "list", code,
                        "code", itemCode,
                        "name", names(before.name()),
                        "sort_order", before.sortOrder(),
                        "active", before.active()),
                Map.of(
                        "list", code,
                        "code", itemCode,
                        "name", names(request.name()),
                        "sort_order", request.sortOrder(),
                        "active", request.active()));
        changed(code);
        return item(row, itemCode);
    }

    private ListRow row(String code) {
        if (!CODE.matcher(code).matches()) throw notFound();
        return lists.find(code).orElseThrow(StudioListService::notFound);
    }

    private Item item(ListRow row, String itemCode) {
        if (!ITEM_CODE.matcher(itemCode).matches()) throw itemNotFound();
        return lists.item(row.id(), itemCode).orElseThrow(StudioListService::itemNotFound);
    }

    /** The cached items of the list are dropped now and again after the commit, so no node keeps the old names. */
    private void changed(String code) {
        String reference = StudioListEnums.reference(code);
        enums.evict(reference);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enums.evict(reference);
                }
            });
        }
    }

    private static void requireNames(Labels name) {
        List<FieldErrorItem> problems = new ArrayList<>();
        check(name.ru(), "name.ru", problems);
        check(name.uz(), "name.uz", problems);
        check(name.en(), "name.en", problems);
        if (!problems.isEmpty()) throw ApiException.validation("error.studio.list_invalid", problems);
    }

    private static void check(@Nullable String name, String at, List<FieldErrorItem> problems) {
        if (name == null || !MdSimpleFormDeclarations.validLabel(name))
            problems.add(FieldErrorItem.keyed(at, "label", "error.md.simple_form_label"));
    }

    private static Map<String, String> names(Labels name) {
        return Map.of("ru", name.ru(), "uz", name.uz(), "en", name.en());
    }

    private static ListSummary summary(ListRow row) {
        return new ListSummary(
                row.code(),
                row.name(),
                row.items(),
                row.activeItems(),
                row.revision(),
                row.createdAt(),
                row.modifiedAt());
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.list_not_found");
    }

    private static ApiException itemNotFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.list_item_not_found");
    }
}
