package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the live forms an automation names (ADR-0046): the declaration of the active version and whether the form is
 * archived. A code that names no live form, or a form whose stored declaration no longer reads, is no form.
 */
@Component
public class AutomationForms {
    /** The code of a live form. */
    public static final Pattern CODE = Pattern.compile("studio\\.[a-z][a-z0-9_]{0,62}");

    private static final Logger log = LoggerFactory.getLogger(AutomationForms.class);

    private final StudioAutomationRepository automations;
    private final StudioFormVersionRepository versions;

    public AutomationForms(StudioAutomationRepository automations, StudioFormVersionRepository versions) {
        this.automations = automations;
        this.versions = versions;
    }

    public Optional<AutomationForm> find(String code) {
        if (!CODE.matcher(code).matches()) return Optional.empty();
        return automations.form(code).flatMap(row -> {
            try {
                return Optional.of(new AutomationForm(code, versions.declaration(row.declaration()), row.archived()));
            } catch (RuntimeException unreadable) {
                log.warn("studio_automation_form_unreadable form={} error={}", code, unreadable.toString());
                return Optional.empty();
            }
        });
    }
}
