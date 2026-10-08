package com.smartup24.cms.instance.common.security;

import java.util.Map;

/**
 * Records a security event in the journal of security events. A platform contract like
 * {@code common.actor.AuditActorContext}: an infrastructure module that may not depend on audit, the owner of the
 * journal, writes through this interface, and audit implements it (plan 10/10, item 1.3).
 */
public interface SecurityEventLog {

    /** One event of the given type; the details are redacted before they are stored. */
    void logSecurityEvent(String eventType, Long userId, String ip, String userAgent, Map<String, Object> details);
}
