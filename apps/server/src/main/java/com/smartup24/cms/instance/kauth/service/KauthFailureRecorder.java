package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthOtpCodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Records refusals in a transaction of their own (plan 10/10, item 0.6).
 *
 * <p>A refused login or a wrong one-time code throws inside the caller's transaction, and the rollback used to erase
 * the very record that limits guessing: the lockout never counted a failure, a code never lost an attempt, and the
 * security log never saw a refusal. The callers stay transactional, their atomicity is needed elsewhere; only the
 * trace of a refusal is committed apart.
 */
@Component
public class KauthFailureRecorder {

    private final KauthLoginAttemptRepository attemptRepository;
    private final KauthOtpCodeRepository otpCodeRepository;
    private final AuditLogService auditLogService;

    public KauthFailureRecorder(KauthLoginAttemptRepository attemptRepository,
                                KauthOtpCodeRepository otpCodeRepository,
                                AuditLogService auditLogService) {
        this.attemptRepository = attemptRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.auditLogService = auditLogService;
    }

    /** A refused login attempt: the row the lockout counts and the security event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginRefused(String eventType, String login, String ip, String userAgent, Long userId, String reason) {
        attemptRepository.recordAttempt(login, ip, false, reason);
        Map<String, Object> details = new HashMap<>();
        details.put("login", login);
        details.put("reason", reason);
        auditLogService.logSecurityEvent(eventType, userId, ip, userAgent, details);
    }

    /** A wrong one-time code uses up one attempt of that code. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void otpAttemptSpent(Long otpId) {
        otpCodeRepository.decrementAttempts(otpId);
    }
}
