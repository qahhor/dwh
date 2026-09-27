package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Разбор и проверка запроса к списку по реестру. DSL фильтра — JSON-массив условий, соединённых «и»:
 * <pre>[{"field":"code","op":"starts_with","value":"sales."},{"field":"periodicity","op":"in","value":["month","year"]}]</pre>
 * Сортировка — ключ поля, с минусом для убывания ({@code -name}). Любая ошибка — 422 со списком полей,
 * по которому клиент подсветит условие: {@code filter[1].op}, {@code sort}, {@code limit}, {@code cursor}.
 */
public final class QueryCompiler {

    public static final String QUERY_INVALID = "QUERY_INVALID";
    public static final String INVALID_LIMIT = "INVALID_LIMIT";
    public static final String INVALID_CURSOR = "INVALID_CURSOR";
    public static final String FILTER_INVALID = "QUERY_FILTER_INVALID";
    public static final String FILTER_TOO_LONG = "QUERY_FILTER_TOO_LONG";
    public static final String UNKNOWN_FIELD = "QUERY_UNKNOWN_FIELD";
    public static final String OP_NOT_ALLOWED = "QUERY_OP_NOT_ALLOWED";
    public static final String VALUE_INVALID = "QUERY_VALUE_INVALID";
    public static final String SORT_INVALID = "QUERY_SORT_INVALID";
    public static final String SEARCH_INVALID = "QUERY_SEARCH_INVALID";

    /** Больше условий человек в фильтре не собирает; ограничение защищает базу от гигантских запросов. */
    public static final int MAX_CONDITIONS = 20;
    public static final int MAX_IN_VALUES = 100;
    private static final int MAX_FILTER_CHARS = 16_384;
    public static final int MAX_SEARCH_CHARS = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private QueryCompiler() {
    }

    public static QueryPlan compile(QueryList list, String filter, String sort, Integer limit, String cursor) {
        return compile(list, filter, sort, limit, cursor, null);
    }

    /**
     * @param search свободный поиск {@code q}: подстрока в любом поле с {@code searchable}, без учёта регистра;
     *               пустой — без поиска
     */
    public static QueryPlan compile(QueryList list, String filter, String sort, Integer limit, String cursor,
                                    String search) {
        return compile(list, filter, sort, limit, cursor, search, null);
    }

    /**
     * @param narrowing параметры модуля, сужающие список помимо DSL (например, прежние плоские фильтры), в
     *                  канонической строке. Они входят в отпечаток курсора: курсор от другого набора
     *                  параметров отвергается так же, как от другого фильтра. {@code null} — таких параметров нет
     */
    public static QueryPlan compile(QueryList list, String filter, String sort, Integer limit, String cursor,
                                    String search, String narrowing) {
        int pageSize = limit == null ? list.defaultLimit() : limit;
        if (pageSize < 1 || pageSize > list.maxLimit()) {
            throw ApiException.validation(INVALID_LIMIT, List.of(new FieldErrorItem("limit", INVALID_LIMIT,
                    "limit must be between 1 and " + list.maxLimit())));
        }

        List<FieldErrorItem> errors = new ArrayList<>();
        List<QueryPlan.Condition> conditions = parseFilter(list, filter, errors);
        QueryField sortField = list.field(list.defaultSort()).orElseThrow();
        boolean descending = list.defaultDescending();
        if (sort != null && !sort.isBlank()) {
            boolean minus = sort.startsWith("-");
            Optional<QueryField> requested = list.viewerField(minus ? sort.substring(1) : sort);
            if (requested.isEmpty() || !requested.get().sortable()) {
                errors.add(new FieldErrorItem("sort", SORT_INVALID, "not a sortable field: " + sort));
            } else {
                sortField = requested.get();
                descending = minus;
            }
        }
        String term = search == null || search.isBlank() ? null : search.strip();
        if (term != null && (term.length() > MAX_SEARCH_CHARS
                || list.viewerFields().stream().noneMatch(QueryField::searchable))) {
            errors.add(new FieldErrorItem("q", SEARCH_INVALID, "search is too long or the list has no searchable field"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(QUERY_INVALID, errors);
        }

        String fingerprint = fingerprint(list, conditions, sortField, descending, term, narrowing);
        QueryCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = QueryCursor.decode(cursor, fingerprint, sortField);
            if (decoded == null) {
                throw ApiException.validation(INVALID_CURSOR, List.of(new FieldErrorItem("cursor", INVALID_CURSOR,
                        "cursor is malformed or belongs to another filter or sort")));
            }
        }
        Set<String> hidden = list.fields().stream().filter(field -> !field.visibleToViewer())
                .map(QueryField::key).collect(Collectors.toUnmodifiableSet());
        return new QueryPlan(list, conditions, sortField, descending, pageSize, decoded, fingerprint, term, hidden);
    }

    private static List<QueryPlan.Condition> parseFilter(QueryList list, String filter, List<FieldErrorItem> errors) {
        List<QueryPlan.Condition> conditions = new ArrayList<>();
        if (filter == null || filter.isBlank()) {
            return conditions;
        }
        if (filter.length() > MAX_FILTER_CHARS) {
            errors.add(new FieldErrorItem("filter", FILTER_TOO_LONG, "filter is too long"));
            return conditions;
        }
        JsonNode root;
        try {
            root = JSON.readTree(filter);
        } catch (JacksonException e) {
            errors.add(new FieldErrorItem("filter", FILTER_INVALID, "filter is not JSON"));
            return conditions;
        }
        if (!root.isArray()) {
            errors.add(new FieldErrorItem("filter", FILTER_INVALID, "filter must be an array of conditions"));
            return conditions;
        }
        int total = 0;
        for (JsonNode node : root) {
            total += node.isObject() && node.has("any") && node.get("any").isArray() ? node.get("any").size() : 1;
        }
        if (total > MAX_CONDITIONS) {
            errors.add(new FieldErrorItem("filter", FILTER_TOO_LONG, "at most " + MAX_CONDITIONS + " conditions"));
            return conditions;
        }
        int groups = 0;
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String at = "filter[" + i + "]";
            if (node.isObject() && node.has("any")) {
                // {"any": [...]} — the conditions inside hold when any of them does (ADR-0016, 2.3; roadmap item 53).
                JsonNode any = node.get("any");
                if (!any.isArray() || any.size() < 2 || node.size() != 1) {
                    errors.add(new FieldErrorItem(at, FILTER_INVALID, "a group is {\"any\": [two or more conditions]}"));
                    continue;
                }
                int group = groups++;
                for (int j = 0; j < any.size(); j++) {
                    JsonNode inner = any.get(j);
                    if (inner.isObject() && inner.has("any")) {
                        errors.add(new FieldErrorItem(at + ".any[" + j + "]", FILTER_INVALID, "groups do not nest"));
                        continue;
                    }
                    parseCondition(list, inner, at + ".any[" + j + "]", errors)
                            .map(condition -> condition.inGroup(group))
                            .ifPresent(conditions::add);
                }
                continue;
            }
            parseCondition(list, node, at, errors).ifPresent(conditions::add);
        }
        return conditions;
    }

    private static Optional<QueryPlan.Condition> parseCondition(QueryList list, JsonNode node, String at,
                                                                List<FieldErrorItem> errors) {
        if (!node.isObject()) {
            errors.add(new FieldErrorItem(at, FILTER_INVALID, "condition must be an object"));
            return Optional.empty();
        }
        String key = node.path("field").asString("");
        Optional<QueryField> found = list.viewerField(key);
        if (found.isEmpty() || !found.get().filterable()) {
            errors.add(new FieldErrorItem(at + ".field", UNKNOWN_FIELD, "not a filterable field: " + key));
            return Optional.empty();
        }
        QueryField field = found.get();
        Optional<QueryOp> op = QueryOp.fromWire(node.path("op").asString(""));
        Set<QueryOp> allowed = field.ops();
        if (op.isEmpty() || !allowed.contains(op.get())) {
            errors.add(new FieldErrorItem(at + ".op", OP_NOT_ALLOWED, "operation not allowed for " + key));
            return Optional.empty();
        }
        List<Object> values = new ArrayList<>();
        if (!parseValues(field, op.get(), node.get("value"), values)) {
            errors.add(new FieldErrorItem(at + ".value", VALUE_INVALID, "value does not fit " + key));
            return Optional.empty();
        }
        return Optional.of(new QueryPlan.Condition(field, op.get(), values));
    }

    private static boolean parseValues(QueryField field, QueryOp op, JsonNode value, List<Object> into) {
        int arity = op.arity();
        if (arity == 0) {
            return value == null || value.isNull();
        }
        if (value == null || value.isNull()) {
            return false;
        }
        List<JsonNode> items = new ArrayList<>();
        if (arity == 1) {
            items.add(value);
        } else {
            if (!value.isArray()) {
                return false;
            }
            value.forEach(items::add);
            if (arity == 2 ? items.size() != 2 : items.isEmpty() || items.size() > MAX_IN_VALUES) {
                return false;
            }
        }
        boolean text = op == QueryOp.CONTAINS || op == QueryOp.STARTS_WITH;
        for (JsonNode item : items) {
            if (!(item.isString() || item.isNumber() || item.isBoolean())) {
                return false;
            }
            try {
                Object parsed = QueryValues.parse(field, item.asString());
                if (text && ((String) parsed).isEmpty()) {
                    return false;
                }
                into.add(parsed);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    private static String fingerprint(QueryList list, List<QueryPlan.Condition> conditions, QueryField sort,
                                      boolean descending, String search, String narrowing) {
        StringBuilder canonical = new StringBuilder(list.code()).append('|').append(descending ? '-' : '+')
                .append(sort.key()).append("|q").append(search == null ? -1 : search.length()).append('=')
                .append(search == null ? "" : search);
        if (narrowing != null) {
            canonical.append("|m").append(narrowing.length()).append('=').append(narrowing);
        }
        for (QueryPlan.Condition condition : conditions) {
            canonical.append('|');
            if (condition.group() >= 0) {
                canonical.append("g").append(condition.group()).append('/');
            }
            canonical.append(condition.field().key()).append(':').append(condition.op().wire());
            for (Object value : condition.values()) {
                canonical.append(':').append(QueryValues.format(condition.field().type(), value).length())
                        .append('=').append(QueryValues.format(condition.field().type(), value));
            }
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
