package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.md.service.UserSessionInvalidator;
import org.springframework.stereotype.Component;

/**
 * Реализация порта md.UserSessionInvalidator (инвариант I-U1):
 * закрывает все сессии и отзывает все API-токены пользователя.
 * Вызывается внутри транзакции блокировки (MdUserService.setUserState).
 */
@Component
public class KauthUserSessionInvalidator implements UserSessionInvalidator {

    private final KauthSessionRepository sessionRepository;
    private final KauthApiTokenRepository apiTokenRepository;

    public KauthUserSessionInvalidator(KauthSessionRepository sessionRepository,
                                       KauthApiTokenRepository apiTokenRepository) {
        this.sessionRepository = sessionRepository;
        this.apiTokenRepository = apiTokenRepository;
    }

    @Override
    public void invalidateAllAccess(Long userId) {
        sessionRepository.closeAllUserSessions(userId);
        apiTokenRepository.revokeAllUserTokens(userId);
    }
}
