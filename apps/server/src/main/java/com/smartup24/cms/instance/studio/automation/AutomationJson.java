package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Trigger;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.ActionResult;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Problem;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON columns of automations and their runs (ADR-0046), through the application's one mapper: the trigger and the
 * actions as the API names them, a run's payload, problem and results.
 */
@Component
public class AutomationJson {
    private static final TypeReference<List<Action>> ACTIONS = new TypeReference<>() {};
    private static final TypeReference<List<ActionResult>> RESULTS = new TypeReference<>() {};

    private final JsonColumns columns;
    private final ObjectMapper mapper;

    public AutomationJson(ObjectMapper mapper) {
        this.mapper = mapper;
        this.columns = new JsonColumns(mapper, "studio_automations");
    }

    public String write(Object value) {
        return columns.write(value);
    }

    public Trigger trigger(String json) {
        return columns.read(json, Trigger.class);
    }

    public List<Action> actions(String json) {
        return columns.read(json, ACTIONS);
    }

    public Map<String, Object> object(@Nullable String json) {
        return columns.readObject(json);
    }

    public List<ActionResult> results(String json) {
        return columns.read(json, RESULTS);
    }

    public @Nullable Problem problem(@Nullable String json) {
        return json == null || json.isBlank() ? null : columns.read(json, Problem.class);
    }

    /** A body of the runtime: the values by key as a JSON object. */
    public JsonNode body(Map<String, @Nullable Object> values) {
        return mapper.valueToTree(values);
    }

    /** The stored automation as a request, for the check of a run and of a new version of its form. */
    public AutomationRequest request(AutomationRow row) {
        return new AutomationRequest(
                row.form(),
                row.name(),
                row.active(),
                trigger(row.trigger()),
                row.condition(),
                actions(row.actions()),
                row.timeZone());
    }
}
