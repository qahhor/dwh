package com.greenwhite.dwh.cp.security;

import com.greenwhite.dwh.common.crypto.Argon2idPasswordHasher;
import org.springframework.stereotype.Component;

/**
 * Адаптер хэшера паролей Control Plane к единой библиотеке platform-common.
 */
@Component
public class CpPasswordHasher {

    private final Argon2idPasswordHasher delegate;

    public CpPasswordHasher() {
        this(new Argon2idPasswordHasher());
    }

    public CpPasswordHasher(Argon2idPasswordHasher delegate) {
        this.delegate = delegate;
    }

    public String hashPassword(String raw) {
        return delegate.hashPassword(raw);
    }

    public boolean verifyPassword(String raw, String hash) {
        return delegate.verifyPassword(raw, hash);
    }

    public static String sha256(String raw) {
        return Argon2idPasswordHasher.sha256(raw);
    }
}
