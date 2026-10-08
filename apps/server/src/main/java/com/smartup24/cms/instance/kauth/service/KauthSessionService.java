package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.api.ActiveSessionView;
import com.smartup24.cms.instance.kauth.api.LoginAttemptView;
import com.smartup24.cms.instance.kauth.api.SessionView;
import com.smartup24.cms.instance.kauth.api.UserSecuritySummary;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.service.MdUserService;
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

    /** Records the use of a session, at most once per touch interval (ADR-0034). */
    @Transactional
    public void touch(KauthSessionRepository.SessionRecord session) {
        sessionRepository.touch(session);
    }

    @Transactional
    public void closeSession(Long sessionId) {
        sessionRepository.close(sessionId);
    }

    /**
     * Closes one session of a user. A session of someone else, a closed one and an unknown id all answer "not found":
     * a caller learns nothing about sessions that are not the user's.
     */
    @Transactional
    public void closeUserSession(Long userId, Long sessionId) {
        if (sessionRepository.closeOwned(sessionId, userId) == 0) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.auth.session_not_found");
        }
    }

    @Transactional
    public void closeAllUserSessions(Long userId) {
        sessionRepository.closeAllUserSessions(userId);
    }

    @Transactional
    public void closeOtherSessions(Long userId, Long currentSessionId) {
        sessionRepository.closeOtherSessions(userId, currentSessionId);
    }

    /** Housekeeping of expired sessions; the active condition already refuses them (ADR-0034). */
    @Transactional
    public int closeExpiredSessions() {
        return sessionRepository.closeExpiredSessions();
    }
}
