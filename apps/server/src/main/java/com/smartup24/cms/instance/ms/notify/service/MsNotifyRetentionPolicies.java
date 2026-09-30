package com.smartup24.cms.instance.ms.notify.service;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The journals of notifications and how long they live (plan 10/10, item 3.13). */
@Configuration(proxyBeanMethods = false)
public class MsNotifyRetentionPolicies {

    /** The inbox keeps half a year; an older notice, read or not, no longer calls for action. */
    @Bean
    RetentionPolicy inboxRetention() {
        return new RetentionPolicy("inbox", "ms_notifications", "created_at < :cutoff", 180);
    }

    /** Sent and given-up deliveries only: a pending one is never deleted, however old. */
    @Bean
    RetentionPolicy notificationOutboxRetention() {
        return new RetentionPolicy(
                "notification-outbox",
                "ms_notification_outbox",
                "status in ('SENT', 'DEAD_LETTER') and processed_at < :cutoff",
                30);
    }
}
