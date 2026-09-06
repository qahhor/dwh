package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.md.service.UserSessionInvalidator;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта md.UserSessionInvalidator (инвариант I-U1):
 * повышает версию доступа, закрывает сессии и отзывает API-токены.
 * REQUIRED сохраняет атомарность как внутри MD use case, так и при отдельном вызове.
 */
@Component
public class KauthUserSessionInvalidator implements UserSessionInvalidator {

    private final KauthSessionRepository sessionRepository;
    private final KauthApiTokenRepository apiTokenRepository;
    private final MdUserRepository userRepository;

    public KauthUserSessionInvalidator(KauthSessionRepository sessionRepository,
                                       KauthApiTokenRepository apiTokenRepository,
                                       MdUserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void invalidateAllAccess(Long userId) {
        userRepository.incrementAuthenticationVersion(userId);
        sessionRepository.closeAllUserSessions(userId);
        apiTokenRepository.revokeAllUserTokens(userId);
    }
}
