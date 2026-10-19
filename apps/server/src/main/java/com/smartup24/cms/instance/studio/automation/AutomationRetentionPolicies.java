package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** How long the run log of automations keeps its rows (ADR-0046, ADR-0025). */
@Configuration(proxyBeanMethods = false)
public class AutomationRetentionPolicies {

    /** Runs keep three months; a queued run is never deleted. */
    @Bean
    RetentionPolicy automationRunsRetention() {
        return new RetentionPolicy(
                "automation-runs", "studio_automation_runs", "status <> 'queued' and created_at < :cutoff", 90);
    }
}
