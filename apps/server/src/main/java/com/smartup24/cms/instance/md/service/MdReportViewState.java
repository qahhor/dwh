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
 *  "filter": [...], "chart": "bar"}</pre>
 * The chart is {@code table}, {@code bar} (needs a grouping) or {@code kpi} (one figure, no grouping). Errors point
 * inside {@code state}: {@code state.groupBy[0].field}, {@code state.chart}.
 */
@Component
public class MdReportViewState {

    public static final String TABLE_CHART = "table";
    public static final String BAR_CHART = "bar";
    public static final String KPI_CHART = "kpi";

    private static final Set<String> KEYS = Set.of("groupBy", "measures", "filter", "chart");
    private static final Set<String> CHARTS = Set.of(TABLE_CHART, BAR_CHART, KPI_CHART);

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
                    list, state.get("groupBy"), state.get("measures"), noFilter ? null : filterNode.toString());
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
        return canonical.toString();
    }

    private static String chart(JsonNode node, QueryAggregate aggregate, List<FieldErrorItem> errors) {
        if (node == null || node.isNull()) return TABLE_CHART;
        String chart = node.isString() ? node.asString() : "";
        boolean grouped = aggregate == null || aggregate.groups().stream().anyMatch(group -> !group.implicit());
        boolean fits = CHARTS.contains(chart)
                && (!BAR_CHART.equals(chart) || grouped)
                && (!KPI_CHART.equals(chart) || aggregate == null || !grouped);
        if (!fits) {
            errors.add(FieldErrorItem.keyed(
                    "state.chart", MdListViewService.LIST_VIEW_INVALID, "error.md.field_list_view_chart_invalid"));
        }
        return chart;
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return ApiException.validation("error.md.list_view_invalid", errors);
    }
}
