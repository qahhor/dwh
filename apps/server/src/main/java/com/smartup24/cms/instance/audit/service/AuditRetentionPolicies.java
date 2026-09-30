package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The journals of the audit module and how long they live (plan 10/10, item 3.13). The audit log itself is not here:
 * its partitions leave the database only through a verified archive (docs/ops/privacy-and-retention-annex.md).
 */
@Configuration(proxyBeanMethods = false)
public class AuditRetentionPolicies {

    /** Sign-ins, lockouts and other security events: a year, the horizon of an incident review. */
    @Bean
    RetentionPolicy securityEventsRetention() {
        return new RetentionPolicy("security-events", "security_events", "created_at < :cutoff", 365);
    }
}
