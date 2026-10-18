package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The history of report mailings and how long it lives (ADR-0045; plan 10/10, item 3.13). */
@Configuration(proxyBeanMethods = false)
public class MailingRetentionPolicies {

    /** Runs keep half a year, their deliveries with them; an unfinished run is never deleted. */
    @Bean
    RetentionPolicy reportMailingRunsRetention() {
        return new RetentionPolicy(
                "report-mailing-runs",
                "report_mailing_runs",
                "status not in ('queued', 'running') and created_at < :cutoff",
                180);
    }
}
