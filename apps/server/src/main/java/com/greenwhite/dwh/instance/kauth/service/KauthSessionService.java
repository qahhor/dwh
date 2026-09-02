package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class KauthSessionService {

    private final KauthSessionRepository sessionRepository;

    public KauthSessionService(KauthSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
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

    @Transactional(readOnly = true)
    public List<KauthSessionRepository.SessionRecord> getUserActiveSessions(Long userId) {
        return sessionRepository.findActiveByUserId(userId);
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
    public int closeInactiveSessions(java.time.Instant cutoff) {
        return sessionRepository.closeInactiveSessions(cutoff);
    }
}

