package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * A new version of a live form keeps its switched-on automations working (ADR-0046): each of them whose trigger reads
 * the form or whose action creates its records is checked against the next version before it is activated, and a
 * version that would break one — a field hidden that it reads or writes, a transition removed that it runs — is refused
 * with 422 naming the automation and its part. A paused automation is checked again when it is switched on.
 */
@Component
public class AutomationCompatibility {
    private final StudioAutomationRepository automations;
    private final AutomationChecks checks;
    private final AutomationForms forms;
    private final AutomationJson json;

    public AutomationCompatibility(
            StudioAutomationRepository automations,
            AutomationChecks checks,
            AutomationForms forms,
            AutomationJson json) {
        this.automations = automations;
        this.checks = checks;
        this.forms = forms;
        this.json = json;
    }

    /** Refuses the next version of {@code code} when a switched-on automation would not run with it. */
    public void require(String code, Declaration next) {
        Function<String, Optional<AutomationForm>> lookup =
                name -> name.equals(code) ? Optional.of(new AutomationForm(code, next, false)) : forms.find(name);
        Map<Long, AutomationRow> affected = new LinkedHashMap<>();
        automations.activeOf(List.of(code)).forEach(row -> affected.put(row.id(), row));
        if (AutomationForms.CODE.matcher(code).matches()) {
            automations.creatingIn(code).forEach(row -> affected.put(row.id(), row));
        }
        List<FieldErrorItem> problems = new ArrayList<>();
        for (AutomationRow row : affected.values()) {
            try {
                checks.check(row.form(), json.request(row), AutomationChecks.Context.run(), lookup);
            } catch (ApiException broken) {
                List<FieldErrorItem> items = broken.getFieldErrors();
                String part =
                        items == null || items.isEmpty() ? "" : items.getFirst().field();
                problems.add(FieldErrorItem.keyed(
                        "automations[" + row.id() + "]",
                        "automation_incompatible",
                        "error.studio.automation_incompatible",
                        Map.of("name", row.name(), "part", part)));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation("error.studio.automation_incompatible_version", problems);
        }
    }
}
