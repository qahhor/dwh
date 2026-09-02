package com.greenwhite.dwh.instance.config.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;

    public IdempotencyService(IdempotencyRepository idempotencyRepository) {
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRepository.IdempotencyRecord> findByKey(UUID key) {
        return idempotencyRepository.findByKey(key);
    }

    @Transactional
    public void save(UUID key, Long userId, String requestHash, int responseStatus, String responseBodyJson) {
        idempotencyRepository.save(key, userId, requestHash, responseStatus, responseBodyJson);
    }

    @Transactional
    public void cleanupOldKeys(int days) {
        Instant cutoff = Instant.now().minusSeconds((long) days * 86400);
        idempotencyRepository.deleteOlderThan(cutoff);
    }

    /**
     * Computes a deterministic SHA-256 hash of the HTTP request.
     */
    public String computeRequestHash(String method, String uri, String query, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.toUpperCase().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(uri.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            if (query != null && !query.isBlank()) {
                digest.update(query.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) ':');
            if (body != null && body.length > 0) {
                digest.update(body);
            }
            byte[] hashBytes = digest.digest();
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
