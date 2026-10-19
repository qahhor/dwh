package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.studio.automation.AutomationSpec;
import org.springframework.stereotype.Component;

/**
 * What an automation asks of its author (ADR-0046): the person who saves it must be able to do by hand everything it
 * does — see the records of its form, change them for a {@code set_field}, run the transition for a
 * {@code transition}, create records of the target form for a {@code create_record}. So an automation never lets its
 * author do more than the author may; the runtime checks the same rights again at every run.
 */
@Component
public class StudioAutomationRights {

    /** Refuses with 403 naming the first right the signed-in person lacks. */
    public void requireAuthor(AutomationSpec spec) {
        require(spec.form(), "view");
        for (AutomationSpec.Step step : spec.actions()) {
            switch (step) {
                case AutomationSpec.SetFields set -> require(spec.form(), "update");
                case AutomationSpec.CreateRecord create -> require(create.form(), "create");
                case AutomationSpec.RunTransition move -> require(spec.form(), move.code());
                case AutomationSpec.Notify notify -> require(spec.form(), "view");
            }
        }
    }

    private static void require(String form, String action) {
        if (!SecurityContext.hasPermission(form, action)) throw ApiException.permissionDenied(form, action);
    }
}
