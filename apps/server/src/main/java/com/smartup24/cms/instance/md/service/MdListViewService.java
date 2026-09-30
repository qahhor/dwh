package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewRequest;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewResponse;
import com.smartup24.cms.instance.md.repository.MdListViewRepository;
import com.smartup24.cms.instance.md.repository.MdListViewRepository.ListView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Сохранённые представления списка (ADR-0016): колонки, сортировка и фильтр, которые пользователь
 * назвал и может вернуть одним выбором. Состояние проверяется по реестру полей и хранится в
 * канонической форме, собранной сервером, так что в базу не попадает ничего, чего реестр не знает.
 */
@Service
public class MdListViewService {

    // Field error codes (errors[].code); the problem itself names its text by key (item 3.1).
    public static final String LIST_VIEW_INVALID = "LIST_VIEW_INVALID";
    public static final String LIST_VIEW_NAME_TAKEN = "LIST_VIEW_NAME_TAKEN";
    public static final String LIST_VIEW_LIMIT = "LIST_VIEW_LIMIT";

    /** Больше представлений одного списка человек не различает в меню. */
    public static final int MAX_VIEWS_PER_LIST = 20;

    public static final int MAX_NAME = 80;

    private static final Pattern WIDTH = Pattern.compile("^\\d{1,4}px$");
    private static final Set<String> STATE_KEYS = Set.of("columns", "sort", "filter");
    private static final Set<String> COLUMN_KEYS = Set.of("order", "hidden", "widths");
    private static final String TABLE = "md_list_views";
    private static final List<String> AUDITED = List.of("list_code", "name", "state", "is_default");

    private final MdListViewRepository repo;
    private final QueryListRegistry registry;
    private final AuditLogService audit;
    /** Builds and reads the view state trees (plan 10/10, item 3.11: the application's mapper). */
    private final ObjectMapper json;

    public MdListViewService(
            MdListViewRepository repo, QueryListRegistry registry, AuditLogService audit, ObjectMapper json) {
        this.repo = repo;
        this.registry = registry;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<ViewResponse> list(long userId, String listCode) {
        visibleList(listCode);
        return repo.list(userId, listCode).stream().map(this::response).toList();
    }

    @Transactional
    public ViewResponse create(long userId, String listCode, ViewRequest data) {
        QueryList list = visibleList(listCode);
        String name = checkName(data.name());
        String state = canonicalState(list, data.state());
        boolean isDefault = Boolean.TRUE.equals(data.isDefault());
        if (repo.count(userId, listCode) >= MAX_VIEWS_PER_LIST) {
            throw ApiException.validation(
                    "error.md.list_view_limit",
                    Map.of("max", MAX_VIEWS_PER_LIST),
                    List.of(new FieldErrorItem(
                            "name", LIST_VIEW_LIMIT, "at most " + MAX_VIEWS_PER_LIST + " views per list")));
        }
        if (isDefault) {
            repo.clearDefault(userId, listCode, null);
        }
        long id;
        try {
            id = repo.insert(userId, listCode, name, state, isDefault);
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        ListView created = repo.find(userId, listCode, id).orElseThrow();
        audit.logChange(TABLE, Long.toString(id), "I", AUDITED, null, row(created));
        return response(created);
    }

    @Transactional
    public ViewResponse update(long userId, String listCode, long id, int lockVersion, ViewRequest data) {
        QueryList list = visibleList(listCode);
        String name = checkName(data.name());
        String state = canonicalState(list, data.state());
        boolean isDefault = Boolean.TRUE.equals(data.isDefault());
        ListView before = repo.find(userId, listCode, id).orElseThrow(MdListViewService::notFound);
        if (isDefault) {
            repo.clearDefault(userId, listCode, id);
        }
        int updated;
        try {
            updated = repo.update(userId, listCode, id, lockVersion, name, state, isDefault);
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        if (updated == 0) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.list_view_stale");
        }
        ListView after = repo.find(userId, listCode, id).orElseThrow();
        audit.logChange(TABLE, Long.toString(id), "U", AUDITED, row(before), row(after));
        return response(after);
    }

    @Transactional
    public void delete(long userId, String listCode, long id) {
        visibleList(listCode);
        ListView before = repo.find(userId, listCode, id).orElseThrow(MdListViewService::notFound);
        repo.delete(userId, listCode, id);
        audit.logChange(TABLE, Long.toString(id), "D", AUDITED, row(before), null);
    }

    /** Список, который пользователь может смотреть; чужой и несуществующий неотличимы, как в {@code query-meta}. */
    private QueryList visibleList(String listCode) {
        return registry.find(listCode)
                .filter(list -> SecurityContext.hasPermission(list.form(), list.action()))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.query_list_not_found"));
    }

    private static String checkName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation(
                    "error.md.list_view_invalid",
                    List.of(new FieldErrorItem(
                            "name", LIST_VIEW_INVALID, "name must be 1 to " + MAX_NAME + " characters")));
        }
        return name;
    }

    /**
     * Проверенное состояние в канонической форме: колонки — только поля списка, ширина — пиксели,
     * сортировка и фильтр — то, что принял бы сам список ({@link QueryCompiler}). Ошибки адресованы
     * внутрь {@code state}: {@code state.columns.order[2]}, {@code state.filter[0].op}, {@code state.sort}.
     */
    String canonicalState(QueryList list, JsonNode state) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (state == null || !state.isObject()) {
            throw invalid(List.of(new FieldErrorItem("state", LIST_VIEW_INVALID, "state must be an object")));
        }
        for (Map.Entry<String, JsonNode> entry : state.properties()) {
            if (!STATE_KEYS.contains(entry.getKey())) {
                errors.add(new FieldErrorItem("state." + entry.getKey(), LIST_VIEW_INVALID, "unknown key"));
            }
        }
        Set<String> keys =
                new HashSet<>(list.fields().stream().map(QueryField::key).toList());
        ObjectNode canonical = json.createObjectNode();
        canonical.set("columns", columns(state.get("columns"), keys, errors));

        JsonNode sortNode = state.get("sort");
        String sort = null;
        if (sortNode != null && !sortNode.isNull()) {
            if (sortNode.isString()) {
                sort = sortNode.asString();
            } else {
                errors.add(new FieldErrorItem("state.sort", LIST_VIEW_INVALID, "sort must be a string"));
            }
        }
        JsonNode filterNode = state.get("filter");
        String filter = filterNode == null || filterNode.isNull() ? null : filterNode.toString();
        try {
            QueryCompiler.compile(list, filter, sort, null, null);
        } catch (ApiException e) {
            for (FieldErrorItem item : e.getFieldErrors()) {
                errors.add(new FieldErrorItem("state." + item.field(), item.code(), item.message()));
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        if (sort == null || sort.isBlank()) {
            canonical.putNull("sort");
        } else {
            canonical.put("sort", sort);
        }
        canonical.set("filter", filterNode == null || filterNode.isNull() ? json.createArrayNode() : filterNode);
        return canonical.toString();
    }

    private ObjectNode columns(JsonNode node, Set<String> keys, List<FieldErrorItem> errors) {
        ObjectNode columns = json.createObjectNode();
        ArrayNode order = columns.putArray("order");
        ArrayNode hidden = columns.putArray("hidden");
        ObjectNode widths = columns.putObject("widths");
        if (node == null || node.isNull()) {
            return columns;
        }
        if (!node.isObject()) {
            errors.add(new FieldErrorItem("state.columns", LIST_VIEW_INVALID, "columns must be an object"));
            return columns;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!COLUMN_KEYS.contains(entry.getKey())) {
                errors.add(new FieldErrorItem("state.columns." + entry.getKey(), LIST_VIEW_INVALID, "unknown key"));
            }
        }
        keyList(node.get("order"), "state.columns.order", keys, order, errors);
        keyList(node.get("hidden"), "state.columns.hidden", keys, hidden, errors);
        JsonNode widthNode = node.get("widths");
        if (widthNode != null && !widthNode.isNull()) {
            if (!widthNode.isObject()) {
                errors.add(new FieldErrorItem("state.columns.widths", LIST_VIEW_INVALID, "widths must be an object"));
            } else {
                for (Map.Entry<String, JsonNode> entry : widthNode.properties()) {
                    String at = "state.columns.widths." + entry.getKey();
                    if (!keys.contains(entry.getKey())) {
                        errors.add(new FieldErrorItem(at, LIST_VIEW_INVALID, "unknown column"));
                    } else if (!entry.getValue().isString()
                            || !WIDTH.matcher(entry.getValue().asString()).matches()) {
                        errors.add(new FieldErrorItem(at, LIST_VIEW_INVALID, "width must be like 180px"));
                    } else {
                        widths.put(entry.getKey(), entry.getValue().asString());
                    }
                }
            }
        }
        return columns;
    }

    private static void keyList(
            JsonNode node, String at, Set<String> keys, ArrayNode into, List<FieldErrorItem> errors) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            errors.add(new FieldErrorItem(at, LIST_VIEW_INVALID, "must be an array of column keys"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isString() || !keys.contains(item.asString()) || !seen.add(item.asString())) {
                errors.add(new FieldErrorItem(at + "[" + i + "]", LIST_VIEW_INVALID, "unknown or repeated column"));
            } else {
                into.add(item.asString());
            }
        }
    }

    /** The state goes out as JSON, not as the stored text; the list code is in the URL already. */
    private ViewResponse response(ListView view) {
        return new ViewResponse(
                view.id(),
                view.name(),
                json.readTree(view.stateJson()),
                view.isDefault(),
                view.lockVersion(),
                view.modifiedAt());
    }

    private static Map<String, Object> row(ListView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("list_code", view.listCode());
        row.put("name", view.name());
        row.put("state", view.stateJson());
        row.put("is_default", view.isDefault());
        return row;
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return ApiException.validation("error.md.list_view_invalid", errors);
    }

    private static ApiException nameTaken() {
        return ApiException.validation(
                "error.md.list_view_name_taken",
                List.of(new FieldErrorItem("name", LIST_VIEW_NAME_TAKEN, "a view with this name already exists")));
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.list_view_not_found");
    }
}
