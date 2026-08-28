package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class KauthApiTokenService {

    private final KauthApiTokenRepository apiTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthApiTokenService(KauthApiTokenRepository apiTokenRepository) {
        this.apiTokenRepository = apiTokenRepository;
    }

    @Transactional
    public CreatedTokenResult createToken(Long userId, String name, Instant expiresAt) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = "dwh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String tokenPrefix = rawToken.substring(0, Math.min(12, rawToken.length()));
        String tokenHash = KauthPasswordHasher.sha256(rawToken);

        var record = apiTokenRepository.create(userId, name, tokenPrefix, tokenHash, expiresAt);
        return new CreatedTokenResult(record, rawToken);
    }

    @Transactional(readOnly = true)
    public Optional<KauthApiTokenRepository.ApiTokenRecord> validateToken(String rawToken) {
        String tokenHash = KauthPasswordHasher.sha256(rawToken);
        return apiTokenRepository.findActiveByTokenHash(tokenHash);
    }

    @Transactional
    public void recordTokenUsage(Long tokenId) {
        apiTokenRepository.updateLastUsed(tokenId);
    }

    @Transactional(readOnly = true)
    public List<KauthApiTokenRepository.ApiTokenRecord> getUserTokens(Long userId) {
        return apiTokenRepository.findByUserId(userId);
    }

    @Transactional
    public void revokeToken(Long tokenId, Long userId) {
        apiTokenRepository.revoke(tokenId, userId);
    }

    public record CreatedTokenResult(
            KauthApiTokenRepository.ApiTokenRecord record,
            String rawSecretToken
    ) {}
}
