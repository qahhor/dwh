package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.service.UserSessionInvalidator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the md.UserSessionInvalidator port (user-blocking invariant, FR-USR-4):
 * closes sessions and revokes API tokens.
 * REQUIRED keeps it atomic both inside an MD use case and when called on its own.
 */
@Component
public class KauthUserSessionInvalidator implements UserSessionInvalidator {

    private final KauthSessionRepository sessionRepository;
    private final KauthApiTokenRepository apiTokenRepository;

    public KauthUserSessionInvalidator(
            KauthSessionRepository sessionRepository, KauthApiTokenRepository apiTokenRepository) {
        this.sessionRepository = sessionRepository;
        this.apiTokenRepository = apiTokenRepository;
    }

    @Override
    @Transactional
    public void invalidateAllAccess(Long userId) {
        sessionRepository.closeAllUserSessions(userId);
        apiTokenRepository.revokeAllUserTokens(userId);
    }
}
