package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.instance.common.security.SecurityEventLog;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The journal of security events as the platform contract {@link SecurityEventLog} offers it to infrastructure modules
 * that may not depend on audit (plan 10/10, item 1.3). A separate bean, so {@link AuditLogService} keeps no interface
 * and stays injectable by its class under any proxy mode.
 */
@Component
public class AuditSecurityEvents implements SecurityEventLog {

    private final AuditLogService audit;

    public AuditSecurityEvents(AuditLogService audit) {
        this.audit = audit;
    }

    @Override
    public void logSecurityEvent(
            String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        audit.logSecurityEvent(eventType, userId, ip, userAgent, details);
    }
}
