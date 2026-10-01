package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.kauth.api.ApiTokenView;
import com.smartup24.cms.instance.kauth.api.CreatedApiToken;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KauthApiTokenService {

    private final KauthApiTokenRepository apiTokenRepository;
    private final KauthCredentialGuard credentialGuard;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthApiTokenService(KauthApiTokenRepository apiTokenRepository, KauthCredentialGuard credentialGuard) {
        this.apiTokenRepository = apiTokenRepository;
        this.credentialGuard = credentialGuard;
    }

    @Transactional
    public CreatedApiToken createToken(KauthPrincipal principal, String name, Instant expiresAt) {
        credentialGuard.requireCurrent(principal);
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = KauthPref.API_TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String tokenPrefix = rawToken.substring(0, Math.min(12, rawToken.length()));
        String tokenHash = KauthPasswordHasher.sha256(rawToken);

        var record = apiTokenRepository.create(
                principal.userId(), principal.authenticationVersion(), name, tokenPrefix, tokenHash, expiresAt);
        return new CreatedApiToken(view(record), rawToken);
    }

    /**
     * The active token with this secret, found by its hash. A value without {@link KauthPref#API_TOKEN_PREFIX} is
     * no token of this server and is not looked up (plan 10/10, item 4.7).
     */
    @Transactional(readOnly = true)
    public Optional<KauthApiTokenRepository.ApiTokenRecord> validateToken(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(KauthPref.API_TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String tokenHash = KauthPasswordHasher.sha256(rawToken);
        return apiTokenRepository.findActiveByTokenHash(tokenHash);
    }

    @Transactional
    public void recordTokenUsage(Long tokenId) {
        apiTokenRepository.updateLastUsed(tokenId);
    }

    @Transactional(readOnly = true)
    public List<ApiTokenView> getUserTokens(Long userId) {
        return apiTokenRepository.findByUserId(userId).stream()
                .map(KauthApiTokenService::view)
                .toList();
    }

    @Transactional
    public void revokeToken(Long tokenId, Long userId) {
        apiTokenRepository.revoke(tokenId, userId);
    }

    /** The token hash and authentication version never leave the server. */
    private static ApiTokenView view(KauthApiTokenRepository.ApiTokenRecord token) {
        return new ApiTokenView(
                token.id(),
                token.userId(),
                token.name(),
                token.tokenPrefix(),
                token.expiresAt(),
                token.createdAt(),
                token.lastUsedAt(),
                token.revokedAt());
    }
}
