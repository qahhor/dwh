package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext.KauthPrincipal;
import com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import org.springframework.stereotype.Component;

/** Revalidates the original server proof without upgrading its authentication generation. */
@Component
public class KauthCredentialGuard {
    private final KauthSessionRepository sessions;
    private final KauthApiTokenRepository tokens;

    public KauthCredentialGuard(KauthSessionRepository sessions, KauthApiTokenRepository tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    public KauthPrincipal requireCurrent(KauthPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.authenticationVersion() < 0) {
            throw ApiException.invalidCredentials();
        }
        if (principal.isApi()) {
            if (principal.sessionId() != null || principal.apiTokenId() == null) throw ApiException.invalidCredentials();
            var token = tokens.findActiveById(principal.apiTokenId()).orElseThrow(ApiException::invalidCredentials);
            if (!principal.userId().equals(token.userId()) || principal.authenticationVersion() != token.authenticationVersion()) {
                throw ApiException.invalidCredentials();
            }
        } else {
            if (principal.sessionId() == null || principal.apiTokenId() != null) throw ApiException.invalidCredentials();
            var session = sessions.findActiveById(principal.sessionId()).orElseThrow(ApiException::invalidCredentials);
            if (!principal.userId().equals(session.userId()) || principal.authenticationVersion() != session.authenticationVersion()) {
                throw ApiException.invalidCredentials();
            }
        }
        return principal;
    }
}
