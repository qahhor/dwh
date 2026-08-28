package com.greenwhite.dwh.cp.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Argon2id для паролей и SHA-256 для токенов — те же параметры, что в экземпляре. */
@Component
public class CpPasswordHasher {

    private static final int ITERATIONS = 2;
    private static final int MEMORY = 65536;
    private static final int PARALLELISM = 2;

    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

    public String hashPassword(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Пароль не может быть пустым");
        }
        char[] chars = raw.toCharArray();
        try {
            return argon2.hash(ITERATIONS, MEMORY, PARALLELISM, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    public boolean verifyPassword(String raw, String hash) {
        if (raw == null || hash == null) {
            return false;
        }
        char[] chars = raw.toCharArray();
        try {
            return argon2.verify(hash, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    /** Токены (сессии, heartbeat) хранятся только как SHA-256. */
    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
