package com.smartup24.cms.instance.support;

import com.smartup24.cms.instance.common.security.StoredSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The key of stored secrets in tests (ADR-0029): derived from a fixed seed, so every test context of one build opens
 * what another context stored in the shared test database. It is neither the development key nor a real one.
 */
public final class TestStoredSecrets {

    private static final byte[] KEY = derive("smartupcms-test-secrets-key");

    private TestStoredSecrets() {}

    /** The key as {@code SMC_SECRETS_KEY} carries it. */
    public static String keyBase64() {
        return Base64.getEncoder().encodeToString(KEY);
    }

    /** Stored secrets over the test key. */
    public static StoredSecrets secrets() {
        return StoredSecrets.withKey(KEY);
    }

    private static byte[] derive(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JDK", e);
        }
    }
}
