package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.api.MdListViewDtos.REPORT;
import static com.smartup24.cms.instance.md.api.MdListViewDtos.TABLE;
import static com.smartup24.cms.instance.md.api.MdListViewDtos.WIDGET;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saved list views (ADR-0016): columns, sort and filter that the user named and can restore with one
 * choice. The state is validated against the field registry and stored in a canonical form built by the
 * server, so nothing the registry does not know reaches the database. A view is a table view, a report or a widget
 * (ADR-0032, 10.2): the state of a report is checked as the report would run ({@link MdReportViewState}).
 */
@Service
public class MdListViewService {

    // Field error codes (errors[].code); the problem itself names its text by key (item 3.1).
    public static final String LIST_VIEW_INVALID = "LIST_VIEW_INVALID";
    public static final String LIST_VIEW_NAME_TAKEN = "LIST_VIEW_NAME_TAKEN";
    public static final String LIST_VIEW_LIMIT = "LIST_VIEW_LIMIT";

    /** A person cannot tell apart more views of one list in a menu. */
    public static final int MAX_VIEWS_PER_LIST = 20;

    public static final int MAX_NAME = 80;

    /** A dashboard shows this many widgets of one person at most (ADR-0032, 10.2). */
    public static final int MAX_WIDGETS = 12;

    private static final Pattern WIDTH = Pattern.compile("^\\d{1,4}px$");
    private static final Set<String> STATE_KEYS = Set.of("columns", "sort", "filter");
    private static final Set<String> COLUMN_KEYS = Set.of("order", "hidden", "widths");
    private static final String TABLE = "md_list_views";
    private static final List<String> AUDITED = List.of("list_code", "kind", "name", "state", "is_default");
    private static final Set<String> KINDS = Set.of(TABLE, REPORT, WIDGET);

    private final MdListViewRepository repo;
    private final QueryListRegistry registry;
    private final AuditLogService audit;
    private final MdReportViewState reports;
    private final EntityReports entityReports;
    /** Builds and reads the view state trees (plan 10/10, item 3.11: the application's mapper). */
    private final ObjectMapper json;

    public MdListViewService(
            MdListViewRepository repo,
            QueryListRegistry registry,
            AuditLogService audit,
            MdReportViewState reports,
            EntityReports entityReports,
            ObjectMapper json) {
        this.repo = repo;
        this.registry = registry;
        this.audit = audit;
        this.reports = reports;
        this.entityReports = entityReports;
        this.json = json;
    }

    /** The user's views of the list of the given kinds; no kinds means the table views. */
    @Transactional(readOnly = true)
    public List<ViewResponse> list(long userId, String listCode, @Nullable List<String> kinds) {
        visibleList(listCode);
        List<String> wanted = kinds == null || kinds.isEmpty() ? List.of(TABLE) : List.copyOf(kinds);
        for (String kind : wanted) {
            if (!KINDS.contains(kind)) throw invalid(List.of(kindProblem()));
        }
        return repo.list(userId, listCode, wanted).stream().map(this::response).toList();
    }

    @Transactional
    public ViewResponse create(long userId, String listCode, ViewRequest data) {
        QueryList list = visibleList(listCode);
        ListView checked = check(list, data);
        if (repo.count(userId, listCode, sameClass(checked.kind())) >= MAX_VIEWS_PER_LIST) {
            throw ApiException.validation(
                    "error.md.list_view_limit",
                    Map.of("max", MAX_VIEWS_PER_LIST),
                    List.of(FieldErrorItem.keyed(
                            "name", LIST_VIEW_LIMIT, "error.md.list_view_limit", Map.of("max", MAX_VIEWS_PER_LIST))));
        }
        if (WIDGET.equals(checked.kind())) requireWidgetRoom(userId);
        if (checked.isDefault()) {
            repo.clearDefault(userId, listCode, null);
        }
        long id;
        try {
            id = repo.insert(
                    userId, listCode, checked.kind(), checked.name(), checked.stateJson(), checked.isDefault());
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
        ListView checked = check(list, data);
        ListView before = repo.find(userId, listCode, id).orElseThrow(MdListViewService::notFound);
        if (WIDGET.equals(checked.kind()) && !WIDGET.equals(before.kind())) requireWidgetRoom(userId);
        if (checked.isDefault()) {
            repo.clearDefault(userId, listCode, id);
        }
        int updated;
        try {
            updated = repo.update(userId, listCode, id, lockVersion, checked);
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

    /**
     * The view to store: its kind ({@code table} when absent), name, canonical state and default flag. A report or a
     * widget is saved only on an entity's list the viewer may see — its report runs with the entity's scope (ADR-0032,
     * 10.2) — and is never the view a list opens with.
     */
    private ListView check(QueryList list, ViewRequest data) {
        String kind = data.kind() == null ? TABLE : data.kind();
        if (!KINDS.contains(kind)
                || (!TABLE.equals(kind)
                        && entityReports.entityOfList(list.code()).isEmpty())) {
            throw invalid(List.of(kindProblem()));
        }
        boolean isDefault = Boolean.TRUE.equals(data.isDefault());
        if (isDefault && !TABLE.equals(kind)) {
            throw invalid(List.of(
                    FieldErrorItem.keyed("isDefault", LIST_VIEW_INVALID, "error.md.field_list_view_default_table")));
        }
        String name = checkName(data.name());
        String state = TABLE.equals(kind) ? canonicalState(list, data.state()) : reports.canonical(list, data.state());
        return new ListView(0, list.code(), kind, name, state, isDefault, 0, Instant.EPOCH);
    }

    /** The kinds counted together toward a list's limit: the table views, or the reports and widgets. */
    private static List<String> sameClass(String kind) {
        return TABLE.equals(kind) ? List.of(TABLE) : List.of(REPORT, WIDGET);
    }

    private void requireWidgetRoom(long userId) {
        if (repo.countWidgets(userId) >= MAX_WIDGETS) {
            throw ApiException.validation(
                    "error.md.widget_limit",
                    Map.of("max", MAX_WIDGETS),
                    List.of(FieldErrorItem.keyed(
                            "kind", LIST_VIEW_LIMIT, "error.md.widget_limit", Map.of("max", MAX_WIDGETS))));
        }
    }

    private static FieldErrorItem kindProblem() {
        return FieldErrorItem.keyed("kind", LIST_VIEW_INVALID, "error.md.field_list_view_kind_invalid");
    }

    /** A list the user may view; a forbidden and a missing list look the same, as in {@code query-meta}. */
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
                    List.of(FieldErrorItem.keyed(
                            "name",
                            LIST_VIEW_INVALID,
                            "error.md.field_list_view_name_length",
                            Map.of("max", MAX_NAME))));
        }
        return name;
    }

    /**
     * The validated state in canonical form: columns are only the list's fields, widths are pixels,
     * sort and filter are what the list itself would accept ({@link QueryCompiler}). Errors point
     * inside {@code state}: {@code state.columns.order[2]}, {@code state.filter[0].op}, {@code state.sort}.
     */
    String canonicalState(QueryList list, JsonNode state) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (state == null || !state.isObject()) {
            throw invalid(List.of(
                    FieldErrorItem.keyed("state", LIST_VIEW_INVALID, "error.md.field_list_view_state_invalid")));
        }
        for (Map.Entry<String, JsonNode> entry : state.properties()) {
            if (!STATE_KEYS.contains(entry.getKey())) {
                errors.add(
                        FieldErrorItem.keyed("state." + entry.getKey(), LIST_VIEW_INVALID, "error.field.unknown_key"));
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
                errors.add(
                        FieldErrorItem.keyed("state.sort", LIST_VIEW_INVALID, "error.md.field_list_view_sort_invalid"));
            }
        }
        JsonNode filterNode = state.get("filter");
        String filter = filterNode == null || filterNode.isNull() ? null : filterNode.toString();
        try {
            QueryCompiler.compile(list, filter, sort, null, null);
        } catch (ApiException e) {
            for (FieldErrorItem item : e.getFieldErrors()) {
                errors.add(item.at("state." + item.field()));
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
            errors.add(FieldErrorItem.keyed(
                    "state.columns", LIST_VIEW_INVALID, "error.md.field_list_view_columns_invalid"));
            return columns;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!COLUMN_KEYS.contains(entry.getKey())) {
                errors.add(FieldErrorItem.keyed(
                        "state.columns." + entry.getKey(), LIST_VIEW_INVALID, "error.field.unknown_key"));
            }
        }
        keyList(node.get("order"), "state.columns.order", keys, order, errors);
        keyList(node.get("hidden"), "state.columns.hidden", keys, hidden, errors);
        JsonNode widthNode = node.get("widths");
        if (widthNode != null && !widthNode.isNull()) {
            if (!widthNode.isObject()) {
                errors.add(FieldErrorItem.keyed(
                        "state.columns.widths", LIST_VIEW_INVALID, "error.md.field_list_view_widths_invalid"));
            } else {
                for (Map.Entry<String, JsonNode> entry : widthNode.properties()) {
                    String at = "state.columns.widths." + entry.getKey();
                    if (!keys.contains(entry.getKey())) {
                        errors.add(FieldErrorItem.keyed(
                                at, LIST_VIEW_INVALID, "error.field.unknown_column", Map.of("name", entry.getKey())));
                    } else if (!entry.getValue().isString()
                            || !WIDTH.matcher(entry.getValue().asString()).matches()) {
                        errors.add(
                                FieldErrorItem.keyed(at, LIST_VIEW_INVALID, "error.md.field_list_view_width_invalid"));
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
            errors.add(FieldErrorItem.keyed(at, LIST_VIEW_INVALID, "error.md.field_list_view_keys_invalid"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isString() || !keys.contains(item.asString()) || !seen.add(item.asString())) {
                errors.add(FieldErrorItem.keyed(
                        at + "[" + i + "]", LIST_VIEW_INVALID, "error.md.field_list_view_column_repeated"));
            } else {
                into.add(item.asString());
            }
        }
    }

    /** The state goes out as JSON, not as the stored text; the list code is in the URL already. */
    private ViewResponse response(ListView view) {
        return new ViewResponse(
                view.id(),
                view.kind(),
                view.name(),
                json.readTree(view.stateJson()),
                view.isDefault(),
                view.lockVersion(),
                view.modifiedAt());
    }

    private static Map<String, Object> row(ListView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("list_code", view.listCode());
        row.put("kind", view.kind());
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
                List.of(FieldErrorItem.keyed("name", LIST_VIEW_NAME_TAKEN, "error.md.list_view_name_taken")));
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.list_view_not_found");
    }
}
