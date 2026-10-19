package com.smartup24.cms.instance.studio.automation;

import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.FORM;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.CheckRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.DryRunRequest;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.FormRow;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.service.StudioAutomationRights;
import com.smartup24.cms.instance.studio.service.StudioAutomationTrials;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0046: a new version keeps its automations running; a formula and a dry run are answered before a save. */
class AutomationGuardsTest {
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final AutomationChecks checks = mock(AutomationChecks.class);
    private final AutomationForms forms = mock(AutomationForms.class);

    @Test
    @DisplayName("ADR-0046: a version that an automation would not run with is refused, naming it and its part")
    void anIncompatibleVersionIsRefused() {
        AutomationCompatibility compatibility =
                new AutomationCompatibility(automations, checks, forms, AutomationFixtures.JSON);
        var next = AutomationFixtures.form(false).declaration();
        when(automations.activeOf(List.of("other"))).thenReturn(List.of());
        compatibility.require("other", next);

        when(automations.activeOf(List.of(FORM))).thenReturn(List.of(row(1, "saved", "{\"kind\": \"saved\"}", true)));
        when(automations.creatingIn(FORM)).thenReturn(List.of(row(2, "saved", "{\"kind\": \"saved\"}", true)));
        when(checks.check(eq(FORM), any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.automation_invalid"))
                .thenThrow(ApiException.validation(
                        "error.studio.automation_invalid",
                        List.of(FieldErrorItem.keyed("actions[0].values", "x", "error.studio.automation_field"))));
        ApiException refused = catchThrowableOfType(ApiException.class, () -> compatibility.require(FORM, next));
        assertThat(refused.getMessageKey()).isEqualTo("error.studio.automation_incompatible_version");
        assertThat(refused.getFieldErrors())
                .extracting(FieldErrorItem::field)
                .containsExactly("automations[1]", "automations[2]");
    }

    @Test
    @DisplayName("ADR-0046: a form is read only by its code and only while its declaration reads")
    void formsAreReadByTheirCode() {
        StudioFormVersionRepository versions = mock(StudioFormVersionRepository.class);
        AutomationForms real = new AutomationForms(automations, versions);
        assertThat(real.find("md.users")).isEmpty();
        when(automations.form(FORM)).thenReturn(Optional.of(new FormRow("{", false)));
        when(versions.declaration("{")).thenThrow(new IllegalStateException("unreadable"));
        assertThat(real.find(FORM)).isEmpty();
    }

    @Test
    @DisplayName("ADR-0046: a formula's check names its type or its first problem; a dry run needs a form")
    void formulasAndDryRunsAreAnswered() {
        StudioAutomationTrials trials = new StudioAutomationTrials(
                checks,
                forms,
                mock(AutomationDryRuns.class),
                mock(StudioAutomationRights.class),
                mock(MdPermissionService.class),
                mock(EntityRuntime.class));
        when(forms.find(anyString())).thenReturn(Optional.empty());
        when(forms.find(FORM)).thenReturn(Optional.of(AutomationFixtures.form(false)));
        assertThatThrownBy(() -> trials.check(new CheckRequest("studio.none", "saved", "true", null, null)))
                .isInstanceOf(ApiException.class);
        assertThat(trials.check(new CheckRequest(FORM, "updated", "changed.note", null, null))
                        .valid())
                .isTrue();
        assertThat(trials.check(new CheckRequest(FORM, "saved", "null", null, null))
                        .valid())
                .isTrue();
        assertThat(trials.check(new CheckRequest(FORM, "saved", "note", null, null))
                        .reason())
                .isEqualTo("result");
        assertThat(trials.check(new CheckRequest(FORM, "saved", "note >", null, null))
                        .valid())
                .isFalse();
        assertThat(trials.check(new CheckRequest(FORM, "saved", "true", "studio.none", "note"))
                        .reason())
                .isEqualTo("unknown_field");
        assertThat(trials.check(new CheckRequest(FORM, "saved", "true", null, "nobody"))
                        .reason())
                .isEqualTo("unknown_field");
        assertThat(trials.check(new CheckRequest(FORM, "saved", "1", FORM, "review"))
                        .reason())
                .isEqualTo("result");
        assertThat(trials.check(new CheckRequest(FORM, "saved", "note", null, "note"))
                        .valid())
                .isTrue();
        for (String form : java.util.Arrays.asList(null, " ")) {
            ApiException noForm = catchThrowableOfType(
                    ApiException.class,
                    () -> trials.dryRun(new DryRunRequest(
                            9, new AutomationRequest(form, "Проба", true, null, null, List.of(), null))));
            assertThat(noForm.getFieldErrors())
                    .extracting(FieldErrorItem::field)
                    .containsExactly("automation.form");
        }
    }
}
