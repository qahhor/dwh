package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.md.service.PasswordHasher;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class KauthPasswordHasher implements PasswordHasher {

    private static final int ITERATIONS = 2;
    private static final int MEMORY = 65536; // 64 MB
    private static final int PARALLELISM = 2;

    private final Argon2 argon2;

    public KauthPasswordHasher() {
        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    }

    @Override
    public String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        return argon2.hash(ITERATIONS, MEMORY, PARALLELISM, rawPassword.toCharArray());
    }

    @Override
    public boolean verifyPassword(String rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null) {
            return false;
        }
        return argon2.verify(encodedHash, rawPassword.toCharArray());
    }

    @Override
    public String hashToken(String rawToken) {
        return sha256(rawToken);
    }

    public static String sha256(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token cannot be empty");
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
