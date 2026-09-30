package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    private static final Logger log = LoggerFactory.getLogger(QueryCompiler.class);

    private QueryCompiler() {}

    public static QueryPlan compile(
            QueryList list,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable Integer limit,
            @Nullable String cursor) {
        return compile(list, filter, sort, limit, cursor, null);
    }

    /**
     * @param search свободный поиск {@code q}: подстрока в любом поле с {@code searchable}, без учёта регистра;
     *               пустой — без поиска
     */
    public static QueryPlan compile(
            QueryList list,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable Integer limit,
            @Nullable String cursor,
            @Nullable String search) {
        return compile(list, filter, sort, limit, cursor, search, null);
    }

    /**
     * @param narrowing параметры модуля, сужающие список помимо DSL (например, прежние плоские фильтры), в
     *                  канонической строке. Они входят в отпечаток курсора: курсор от другого набора
     *                  параметров отвергается так же, как от другого фильтра. {@code null} — таких параметров нет
     */
    public static QueryPlan compile(
            QueryList list,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable Integer limit,
            @Nullable String cursor,
            @Nullable String search,
            @Nullable String narrowing) {
        int pageSize = limit == null ? list.defaultLimit() : limit;
        if (pageSize < 1 || pageSize > list.maxLimit()) {
            throw ApiException.validation(
                    "error.common.query_limit_invalid",
                    Map.of("max", list.maxLimit()),
                    List.of(FieldErrorItem.keyed(
                            "limit",
                            INVALID_LIMIT,
                            "error.common.query_limit_invalid",
                            Map.of("max", list.maxLimit()))));
        }

        List<FieldErrorItem> errors = new ArrayList<>();
        List<QueryPlan.Condition> conditions = parseFilter(list, filter, errors);
        QueryField sortField = list.field(list.defaultSort()).orElseThrow();
        boolean descending = list.defaultDescending();
        if (sort != null && !sort.isBlank()) {
            boolean minus = sort.startsWith("-");
            Optional<QueryField> requested = list.viewerField(minus ? sort.substring(1) : sort);
            if (requested.isEmpty() || !requested.get().sortable()) {
                errors.add(FieldErrorItem.keyed(
                        "sort", SORT_INVALID, "error.common.field_sort_invalid", Map.of("sort", sort)));
            } else {
                sortField = requested.get();
                descending = minus;
            }
        }
        String term = search == null || search.isBlank() ? null : search.strip();
        if (term != null
                && (term.length() > MAX_SEARCH_CHARS
                        || list.viewerFields().stream().noneMatch(QueryField::searchable))) {
            errors.add(FieldErrorItem.keyed("q", SEARCH_INVALID, "error.common.field_search_invalid"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.common.query_invalid", errors);
        }

        String fingerprint = fingerprint(list, conditions, sortField, descending, term, narrowing);
        QueryCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = QueryCursor.decode(cursor, fingerprint, sortField);
            if (decoded == null) {
                throw ApiException.validation(
                        "error.common.query_cursor_invalid",
                        List.of(FieldErrorItem.keyed("cursor", INVALID_CURSOR, "error.common.query_cursor_invalid")));
            }
        }
        Set<String> hidden = list.fields().stream()
                .filter(field -> !field.visibleToViewer())
                .map(QueryField::key)
                .collect(Collectors.toUnmodifiableSet());
        return new QueryPlan(list, conditions, sortField, descending, pageSize, decoded, fingerprint, term, hidden);
    }

    private static List<QueryPlan.Condition> parseFilter(
            QueryList list, @Nullable String filter, List<FieldErrorItem> errors) {
        List<QueryPlan.Condition> conditions = new ArrayList<>();
        if (filter == null || filter.isBlank()) {
            return conditions;
        }
        if (filter.length() > MAX_FILTER_CHARS) {
            errors.add(FieldErrorItem.keyed("filter", FILTER_TOO_LONG, "error.common.field_filter_too_long"));
            return conditions;
        }
        JsonNode root;
        try {
            root = JSON.readTree(filter);
        } catch (JacksonException e) {
            log.debug("Filter is not JSON: {}", e.getOriginalMessage());
            errors.add(FieldErrorItem.keyed("filter", FILTER_INVALID, "error.common.field_filter_not_json"));
            return conditions;
        }
        if (!root.isArray()) {
            errors.add(FieldErrorItem.keyed("filter", FILTER_INVALID, "error.common.field_filter_not_array"));
            return conditions;
        }
        int total = 0;
        for (JsonNode node : root) {
            total += node.isObject() && node.has("any") && node.get("any").isArray()
                    ? node.get("any").size()
                    : 1;
        }
        if (total > MAX_CONDITIONS) {
            errors.add(FieldErrorItem.keyed(
                    "filter", FILTER_TOO_LONG, "error.common.field_filter_too_many", Map.of("max", MAX_CONDITIONS)));
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
                    errors.add(FieldErrorItem.keyed(at, FILTER_INVALID, "error.common.field_filter_group_invalid"));
                    continue;
                }
                int group = groups++;
                for (int j = 0; j < any.size(); j++) {
                    JsonNode inner = any.get(j);
                    if (inner.isObject() && inner.has("any")) {
                        errors.add(FieldErrorItem.keyed(
                                at + ".any[" + j + "]", FILTER_INVALID, "error.common.field_filter_group_nested"));
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

    private static Optional<QueryPlan.Condition> parseCondition(
            QueryList list, JsonNode node, String at, List<FieldErrorItem> errors) {
        if (!node.isObject()) {
            errors.add(FieldErrorItem.keyed(at, FILTER_INVALID, "error.common.field_filter_condition_invalid"));
            return Optional.empty();
        }
        String key = node.path("field").asString("");
        Optional<QueryField> found = list.viewerField(key);
        if (found.isEmpty() || !found.get().filterable()) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field", UNKNOWN_FIELD, "error.common.field_filter_field_unknown", Map.of("field", key)));
            return Optional.empty();
        }
        QueryField field = found.get();
        Optional<QueryOp> op = QueryOp.fromWire(node.path("op").asString(""));
        Set<QueryOp> allowed = field.ops();
        if (op.isEmpty() || !allowed.contains(op.get())) {
            errors.add(FieldErrorItem.keyed(
                    at + ".op", OP_NOT_ALLOWED, "error.common.field_filter_op_invalid", Map.of("field", key)));
            return Optional.empty();
        }
        List<Object> values = new ArrayList<>();
        if (!parseValues(field, op.get(), node.get("value"), values)) {
            errors.add(FieldErrorItem.keyed(
                    at + ".value", VALUE_INVALID, "error.common.field_filter_value_invalid", Map.of("field", key)));
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

    private static String fingerprint(
            QueryList list,
            List<QueryPlan.Condition> conditions,
            QueryField sort,
            boolean descending,
            @Nullable String search,
            @Nullable String narrowing) {
        StringBuilder canonical = new StringBuilder(list.code())
                .append('|')
                .append(descending ? '-' : '+')
                .append(sort.key())
                .append("|q")
                .append(search == null ? -1 : search.length())
                .append('=')
                .append(search == null ? "" : search);
        if (narrowing != null) {
            canonical.append("|m").append(narrowing.length()).append('=').append(narrowing);
        }
        for (QueryPlan.Condition condition : conditions) {
            canonical.append('|');
            if (condition.group() >= 0) {
                canonical.append("g").append(condition.group()).append('/');
            }
            canonical
                    .append(condition.field().key())
                    .append(':')
                    .append(condition.op().wire());
            for (Object value : condition.values()) {
                canonical
                        .append(':')
                        .append(QueryValues.format(condition.field().type(), value)
                                .length())
                        .append('=')
                        .append(QueryValues.format(condition.field().type(), value));
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
