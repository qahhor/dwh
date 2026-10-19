package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.CheckRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.DryRun;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.DryRunRequest;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Check;
import com.smartup24.cms.instance.studio.automation.AutomationActionChecks;
import com.smartup24.cms.instance.studio.automation.AutomationChecks;
import com.smartup24.cms.instance.studio.automation.AutomationDryRuns;
import com.smartup24.cms.instance.studio.automation.AutomationForm;
import com.smartup24.cms.instance.studio.automation.AutomationForms;
import com.smartup24.cms.instance.studio.automation.AutomationSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Trying an automation before it is saved (ADR-0046): a dry run over a chosen record — the checks of a save, the rights
 * of the person trying it, then the run itself in a transaction that never commits — and the check of one formula while
 * the editor types it. Nothing is stored or sent.
 */
@Service
public class StudioAutomationTrials {
    private final AutomationChecks checks;
    private final AutomationForms forms;
    private final AutomationDryRuns dryRuns;
    private final StudioAutomationRights rights;
    private final MdPermissionService permissions;
    private final EntityRuntime runtime;

    public StudioAutomationTrials(
            AutomationChecks checks,
            AutomationForms forms,
            AutomationDryRuns dryRuns,
            StudioAutomationRights rights,
            MdPermissionService permissions,
            EntityRuntime runtime) {
        this.checks = checks;
        this.forms = forms;
        this.dryRuns = dryRuns;
        this.rights = rights;
        this.permissions = permissions;
        this.runtime = runtime;
    }

    public DryRun dryRun(DryRunRequest request) {
        String form = request.automation().form();
        if (form == null || form.isBlank()) {
            throw ApiException.validation(
                    "error.studio.automation_invalid",
                    List.of(FieldErrorItem.keyed(
                            "automation.form", "automation_invalid", "error.studio.automation_form")));
        }
        AutomationChecks.Context context = new AutomationChecks.Context(
                Set.of("in_app", "email", "telegram"), permissions.getGrantablePairs(), false);
        AutomationSpec spec = checks.check(form, request.automation(), context);
        rights.requireAuthor(spec);
        EntityRecordView record = RequestZone.with(spec.zone(), () -> runtime.get(spec.form(), request.recordId()));
        AutomationForm automationForm = forms.find(spec.form())
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.automation_form"));
        return dryRuns.run(spec, automationForm, request.automation().name(), record);
    }

    /** The type of a formula of an automation, or its first problem with its position. */
    public Check check(CheckRequest request) {
        AutomationForm form = forms.find(request.form())
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.automation_form"));
        Map<String, FormulaType> types = form.types(AutomationSpec.CHANGES.contains(request.trigger()));
        Field field = null;
        if (request.targetField() != null) {
            String target = request.targetForm() == null ? form.code() : request.targetForm();
            Optional<AutomationForm> owner = forms.find(target);
            field = owner.map(found -> found.entered(request.targetField())).orElse(null);
            if (field == null) return refused(new FormulaException("unknown_field", 1, request.targetField()));
        }
        try {
            Formula formula = Formula.compile(request.formula(), types);
            boolean fits = field == null
                    ? formula.type() == FormulaType.BOOL || formula.type() == FormulaType.NULL
                    : AutomationActionChecks.fits(field, formula.type());
            if (!fits)
                return refused(new FormulaException("result", 1, formula.type().wire()));
            return new Check(true, formula.type().wire(), null, null, null, null);
        } catch (FormulaException problem) {
            return refused(problem);
        }
    }

    private static Check refused(FormulaException problem) {
        return new Check(false, null, problem.reason(), problem.position(), problem.name(), problem.messageKey());
    }
}
