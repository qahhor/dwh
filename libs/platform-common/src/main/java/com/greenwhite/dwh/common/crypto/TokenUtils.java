package com.greenwhite.dwh.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Криптографические утилиты для безопасной генерации и хэширования токенов платформы.
 */
public final class TokenUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenUtils() {}

    public static String generateUrlSafeToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String generatePrefixToken(String prefix, int byteLength) {
        return prefix + generateUrlSafeToken(byteLength);
    }

    public static String sha256(String rawToken) {
        return Argon2idPasswordHasher.sha256(rawToken);
    }
}
