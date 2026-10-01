package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The journals of webhook delivery and how long they live (plan 10/10, item 3.13). */
@Configuration(proxyBeanMethods = false)
public class WebhookRetentionPolicies {

    @Bean
    RetentionPolicy webhookLogsRetention() {
        return new RetentionPolicy("webhook-logs", "kwh_logs", "sent_at < :cutoff", 90);
    }

    /** Delivered and given-up events only: a pending one is never deleted, however old. */
    @Bean
    RetentionPolicy webhookOutboxRetention() {
        return new RetentionPolicy(
                "webhook-outbox", "kwh_outbox", "status in ('SENT', 'DEAD_LETTER') and processed_at < :cutoff", 30);
    }
}
