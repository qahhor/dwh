package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Run;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.RunSummary;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.UserRef;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.RunRow;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The answers of the automation API (ADR-0046), built from the stored rows. */
@Component
public class AutomationViews {
    private final AutomationJson json;
    private final AutomationForms forms;

    public AutomationViews(AutomationJson json, AutomationForms forms) {
        this.json = json;
        this.forms = forms;
    }

    public Automation automation(AutomationRow row, @Nullable RunRow last) {
        boolean archived = forms.find(row.form()).map(AutomationForm::archived).orElse(false);
        return new Automation(
                row.id(),
                row.form(),
                row.name(),
                row.active(),
                archived,
                json.trigger(row.trigger()),
                row.condition(),
                json.actions(row.actions()),
                row.timeZone(),
                new UserRef(row.authorId(), row.authorName()),
                row.nextRunAt(),
                last == null
                        ? null
                        : new RunSummary(last.id(), last.status(), last.reason(), last.createdAt(), last.finishedAt()),
                row.revision(),
                row.createdAt(),
                row.modifiedAt());
    }

    public Run run(RunRow row) {
        return new Run(
                row.id(),
                row.kind(),
                row.triggerKind(),
                row.recordId(),
                row.depth(),
                row.status(),
                row.reason(),
                json.problem(row.error()),
                json.results(row.results()),
                row.attempts(),
                row.createdAt(),
                row.finishedAt());
    }
}
