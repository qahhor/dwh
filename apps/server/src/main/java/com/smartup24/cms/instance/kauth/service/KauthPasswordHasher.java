package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.common.crypto.Argon2idPasswordHasher;
import com.smartup24.cms.instance.md.service.PasswordHasher;
import org.springframework.stereotype.Component;

/**
 * Adapts the instance password hasher to the shared platform-common library.
 */
@Component
public class KauthPasswordHasher implements PasswordHasher {

    private final Argon2idPasswordHasher delegate;

    public KauthPasswordHasher() {
        this(new Argon2idPasswordHasher());
    }

    public KauthPasswordHasher(Argon2idPasswordHasher delegate) {
        this.delegate = delegate;
    }

    @Override
    public String hashPassword(String rawPassword) {
        return delegate.hashPassword(rawPassword);
    }

    @Override
    public boolean verifyPassword(String rawPassword, String encodedHash) {
        return delegate.verifyPassword(rawPassword, encodedHash);
    }

    @Override
    public String hashToken(String rawToken) {
        return Argon2idPasswordHasher.sha256(rawToken);
    }

    public static String sha256(String rawToken) {
        return Argon2idPasswordHasher.sha256(rawToken);
    }
}
