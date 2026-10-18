package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The state of a report or a widget (ADR-0032, 10.2; plan 10/10, item 5.8), validated against the list as the report
 * would run ({@link QueryAggregates}) and stored in a canonical form the server builds:
 * <pre>{"groupBy": [{"field": "status"}, {"field": "createdAt", "trunc": "month"}],
 *  "measures": [{"op": "count"}, {"op": "sum", "field": "total"}],
 *  "filter": [...], "chart": "bar", "sort": {"by": "measure", "measure": 0, "direction": "desc"}, "limit": 10}</pre>
 * The chart is table, bar, kpi, line or horizontalBar; the last two need one group and one measure (ADR-0039). Errors point
 * inside {@code state}: {@code state.groupBy[0].field}, {@code state.chart}.
 */
@Component
public class MdReportViewState {

    public static final String TABLE_CHART = "table";
    public static final String BAR_CHART = "bar";
    public static final String KPI_CHART = "kpi";

    private static final Set<String> KEYS =
            Set.of("groupBy", "measures", "filter", "chart", "dataset", "sort", "limit");
    private static final Set<String> CHARTS = Set.of(TABLE_CHART, BAR_CHART, KPI_CHART, "line", "horizontalBar");

    /** Builds the canonical state tree (plan 10/10, item 3.11: the application's mapper). */
    private final ObjectMapper json;

    public MdReportViewState(ObjectMapper json) {
        this.json = json;
    }

    /** The validated state as the JSON text to store. */
    public String canonical(QueryList list, JsonNode state) {
        if (state == null || !state.isObject()) {
            throw invalid(List.of(FieldErrorItem.keyed(
                    "state", MdListViewService.LIST_VIEW_INVALID, "error.md.field_list_view_state_invalid")));
        }
        List<FieldErrorItem> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : state.properties()) {
            if (!KEYS.contains(entry.getKey())) {
                errors.add(FieldErrorItem.keyed(
                        "state." + entry.getKey(), MdListViewService.LIST_VIEW_INVALID, "error.field.unknown_key"));
            }
        }
        JsonNode filterNode = state.get("filter");
        boolean noFilter = filterNode == null || filterNode.isNull();
        QueryAggregate aggregate = null;
        try {
            aggregate = QueryAggregates.compile(
                    list,
                    state.get("groupBy"),
                    state.get("measures"),
                    noFilter ? null : filterNode.toString(),
                    state.get("sort"),
                    state.get("limit"));
        } catch (ApiException e) {
            List<FieldErrorItem> found = e.getFieldErrors();
            if (found == null) throw e;
            found.forEach(item -> errors.add(item.at("state." + item.field())));
        }
        String chart = chart(state.get("chart"), aggregate, errors);
        if (!errors.isEmpty() || aggregate == null) {
            throw invalid(errors);
        }
        ObjectNode canonical = json.createObjectNode();
        ArrayNode groups = canonical.putArray("groupBy");
        for (QueryAggregate.Group group : aggregate.groups()) {
            if (group.implicit()) continue;
            ObjectNode item = groups.addObject().put("field", group.field().key());
            if (group.trunc() != null) item.put("trunc", group.trunc().wire());
        }
        ArrayNode measures = canonical.putArray("measures");
        for (QueryAggregate.Measure measure : aggregate.measures()) {
            ObjectNode item = measures.addObject().put("op", measure.op().wire());
            if (measure.fieldKey() != null) item.put("field", measure.fieldKey());
        }
        canonical.set("filter", noFilter ? json.createArrayNode() : filterNode);
        canonical.put("chart", chart);
        order(canonical, aggregate.order());
        String dataset = dataset(state);
        if (dataset != null) canonical.put("dataset", dataset);
        return canonical.toString();
    }

    /** The order of the groups and the top kept, only when the report asks for one. */
    private static void order(ObjectNode canonical, QueryAggregate.Order order) {
        if (order.by() != QueryAggregate.By.GROUP || order.descending()) {
            ObjectNode sort = canonical.putObject("sort").put("by", order.by().wire());
            if (order.by() == QueryAggregate.By.MEASURE) sort.put("measure", order.measure());
            sort.put("direction", order.descending() ? "desc" : "asc");
        }
        Integer limit = order.limit();
        if (limit != null) canonical.put("limit", limit.intValue());
    }

    private static String chart(JsonNode node, QueryAggregate aggregate, List<FieldErrorItem> errors) {
        if (node == null || node.isNull()) return TABLE_CHART;
        String chart = node.isString() ? node.asString() : "";
        boolean grouped = aggregate == null || aggregate.groups().stream().anyMatch(group -> !group.implicit());
        boolean fits = CHARTS.contains(chart)
                && (!BAR_CHART.equals(chart) || grouped)
                && (!KPI_CHART.equals(chart) || aggregate == null || !grouped)
                && additionalChartFits(chart, aggregate);
        if (!fits) {
            errors.add(FieldErrorItem.keyed(
                    "state.chart", MdListViewService.LIST_VIEW_INVALID, "error.md.field_list_view_chart_invalid"));
        }
        return chart;
    }

    /** A supplied dataset is a closed identifier, not an instruction or a SQL fragment (ADR-0039). */
    public static @Nullable String dataset(JsonNode state) {
        JsonNode node = state == null ? null : state.get("dataset");
        if (node == null) return null;
        if (!node.isString()
                || node.asString().length() > 128
                || !node.asString().matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){1,3}"))
            throw invalid(List.of(FieldErrorItem.keyed(
                    "state.dataset", MdListViewService.LIST_VIEW_INVALID, "error.md.field_list_view_state_invalid")));
        return node.asString();
    }

    private static boolean additionalChartFits(String chart, @Nullable QueryAggregate aggregate) {
        if (aggregate == null || !("line".equals(chart) || "horizontalBar".equals(chart))) return true;
        var explicit =
                aggregate.groups().stream().filter(group -> !group.implicit()).toList();
        if (explicit.size() != 1 || aggregate.measures().size() != 1) return false;
        boolean temporal = explicit.getFirst().trunc() != null;
        return "line".equals(chart) == temporal;
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return ApiException.validation("error.md.list_view_invalid", errors);
    }
}
