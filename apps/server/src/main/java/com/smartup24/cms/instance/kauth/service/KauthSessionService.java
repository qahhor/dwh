package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.api.ActiveSessionView;
import com.smartup24.cms.instance.kauth.api.LoginAttemptView;
import com.smartup24.cms.instance.kauth.api.SessionView;
import com.smartup24.cms.instance.kauth.api.UserSecuritySummary;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.service.MdUserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KauthSessionService {

    private final KauthSessionRepository sessionRepository;
    private final KauthLoginAttemptRepository loginAttemptRepository;

    public KauthSessionService(KauthSessionRepository sessionRepository) {
        this(sessionRepository, null);
    }

    @Autowired
    public KauthSessionService(
            KauthSessionRepository sessionRepository, KauthLoginAttemptRepository loginAttemptRepository) {
        this.sessionRepository = sessionRepository;
        this.loginAttemptRepository = loginAttemptRepository;
    }

    @Transactional(readOnly = true)
    public UserSecuritySummary getUserSecuritySummary(Long userId, MdUserService.AuthUser user) {
        var activeSessions = sessionRepository.findActiveByUserId(userId);
        var recentAttempts = loginAttemptRepository != null
                ? loginAttemptRepository.findRecentAttemptsForLogin(user.login(), 10)
                : List.<KauthLoginAttemptRepository.LoginAttemptRecord>of();
        return new UserSecuritySummary(
                user.id(),
                user.login(),
                user.is2faEnabled(),
                user.forcePasswordChange(),
                null, // passwordChangedAt
                user.createdAt(),
                user.authenticationVersion(),
                activeSessions.size(),
                activeSessions.stream().map(KauthSessionService::view).toList(),
                recentAttempts.stream().map(KauthSessionService::view).toList());
    }

    /** The viewer's own active sessions, the one of this request marked {@code current}. */
    @Transactional(readOnly = true)
    public List<ActiveSessionView> listOwnActiveSessions(Long userId, Long currentSessionId) {
        return sessionRepository.findActiveByUserId(userId).stream()
                .map(s -> new ActiveSessionView(
                        s.id(),
                        s.userId(),
                        s.ip(),
                        s.userAgent(),
                        s.deviceInfo(),
                        s.createdAt(),
                        s.lastSeenAt(),
                        s.closedAt(),
                        currentSessionId != null && currentSessionId.equals(s.id())))
                .toList();
    }

    /** A user's active sessions for an administrator. */
    @Transactional(readOnly = true)
    public List<SessionView> listUserActiveSessions(Long userId) {
        return sessionRepository.findActiveByUserId(userId).stream()
                .map(KauthSessionService::view)
                .toList();
    }

    private static SessionView view(KauthSessionRepository.SessionRecord s) {
        return new SessionView(
                s.id(), s.userId(), s.ip(), s.userAgent(), s.deviceInfo(), s.createdAt(), s.lastSeenAt(), s.closedAt());
    }

    private static LoginAttemptView view(KauthLoginAttemptRepository.LoginAttemptRecord a) {
        return new LoginAttemptView(a.id(), a.login(), a.ip(), a.isSuccess(), a.failureReason(), a.attemptAt());
    }

    @Transactional(readOnly = true)
    public Optional<KauthSessionRepository.SessionRecord> getActiveSession(String rawToken) {
        String tokenHash = KauthPasswordHasher.sha256(rawToken);
        return sessionRepository.findActiveByTokenHash(tokenHash);
    }

    @Transactional
    public void updateLastSeen(Long sessionId) {
        sessionRepository.updateLastSeen(sessionId);
    }

    @Transactional
    public void closeSession(Long sessionId) {
        sessionRepository.close(sessionId);
    }

    @Transactional
    public void closeAllUserSessions(Long userId) {
        sessionRepository.closeAllUserSessions(userId);
    }

    @Transactional
    public void closeOtherSessions(Long userId, Long currentSessionId) {
        sessionRepository.closeOtherSessions(userId, currentSessionId);
    }

    @Transactional
    public int closeInactiveSessions(Instant cutoff) {
        return sessionRepository.closeInactiveSessions(cutoff);
    }
}
