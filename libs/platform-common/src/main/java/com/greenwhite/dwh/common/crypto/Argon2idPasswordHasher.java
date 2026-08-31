package com.greenwhite.dwh.common.crypto;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Единый промышленный хэшер паролей платформы на основе Argon2id и SHA-256 токенов.
 * Соответствует рекомендациям OWASP и PHC (Password Hashing Competition).
 */
@Component
public class Argon2idPasswordHasher {

    private static final int ITERATIONS = 2;
    private static final int MEMORY_KB = 65536; // 64 MB
    private static final int PARALLELISM = 2;

    private final Argon2 argon2;

    public Argon2idPasswordHasher() {
        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    }

    public String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Пароль не может быть пустым");
        }
        char[] chars = rawPassword.toCharArray();
        try {
            return argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    public boolean verifyPassword(String rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        char[] chars = rawPassword.toCharArray();
        try {
            return argon2.verify(encodedHash, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    public static String sha256(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Токен не может быть пустым");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
