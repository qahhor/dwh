package com.smartup24.cms.instance.report.mailing;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The meters of report mailings (ADR-0045): {@code smc_report_mailing_runs_total} by outcome ({@code done},
 * {@code partial}, {@code failed}, {@code skipped}) and {@code smc_report_mailing_deliveries_total} by channel and
 * status. The alert {@code SmartupcmsReportMailingsFailing} watches the failed runs.
 */
@Component
public class MailingMetrics {
    private final @Nullable MeterRegistry registry;

    public MailingMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    public void run(String outcome) {
        if (registry != null)
            registry.counter("smc.report.mailing.runs", "outcome", outcome).increment();
    }

    public void delivery(String channel, String status) {
        if (registry != null) {
            registry.counter("smc.report.mailing.deliveries", "channel", channel, "status", status)
                    .increment();
        }
    }
}
