package com.smartup24.cms.instance.common.security;

import com.smartup24.cms.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Secrets kept in the database, encrypted with the installation key {@code SMC_SECRETS_KEY} (ADR-0029).
 *
 * <p>A repository seals a secret before it writes it and opens it when it reads it, so the rest of the code sees the
 * plain value (a webhook is still signed with its plain key). A stored value without the {@code v1:} prefix is plain
 * text and is refused: no installation predates the encryption, and {@link StoredSecretsCheck} stops the start when a
 * column holds one.
 */
public final class StoredSecrets {

    /** The environment variable of the key, named in every message about it. */
    public static final String KEY_VARIABLE = "SMC_SECRETS_KEY";

    /** The seed of the development key: a fixed key for the dev profile only, never accepted outside it. */
    private static final String DEV_KEY_SEED = "smartupcms-development-secrets-key";

    private final @Nullable SecretCipher cipher;

    private StoredSecrets(@Nullable SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** Secrets over a raw 32-byte key (tests, tools). */
    public static StoredSecrets withKey(byte[] key) {
        return new StoredSecrets(new SecretCipher(key));
    }

    /**
     * Secrets over the configured key.
     *
     * <ul>
     *   <li>a key is set: it must be base64 of 32 bytes, and outside the dev profile it must not be the development
     *       key;
     *   <li>no key and the dev profile: the fixed development key;
     *   <li>no key and the migrate profile: no cipher at all, migrations never touch a secret;
     *   <li>no key otherwise: the start is refused.
     * </ul>
     *
     * @throws IllegalStateException with a message that never repeats the key
     */
    public static StoredSecrets fromConfiguration(@Nullable String configured, boolean dev, boolean migrate) {
        if (configured == null || configured.isBlank()) {
            if (dev) {
                return new StoredSecrets(new SecretCipher(developmentKey()));
            }
            if (migrate) {
                return new StoredSecrets(null);
            }
            throw new IllegalStateException(
                    KEY_VARIABLE + " is not set: the server encrypts the secrets it keeps in the database with it."
                            + " Generate one with `openssl rand -base64 32` (docs/ops/deployment-guide.md).");
        }
        SecretCipher cipher;
        try {
            cipher = SecretCipher.fromBase64(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(KEY_VARIABLE + ": " + e.getMessage(), e);
        }
        if (!dev && isDevelopmentKey(configured)) {
            throw new IllegalStateException(KEY_VARIABLE + " is the development key: set a key of this installation.");
        }
        return new StoredSecrets(cipher);
    }

    /** Whether a stored value is encrypted. */
    public static boolean isSealed(@Nullable String stored) {
        return SecretCipher.isEncrypted(stored);
    }

    /**
     * Encrypts a secret for one column; {@code null} stays {@code null}.
     *
     * @param context the column, e.g. {@code kwh_subscriptions.secret_token}
     */
    public @Nullable String seal(@Nullable String plain, String context) {
        if (plain == null) {
            return null;
        }
        return cipher().encrypt(plain, context);
    }

    /**
     * The plain value of a stored secret; {@code null} stays {@code null}.
     *
     * @throws IllegalStateException when the value is not encrypted, was encrypted with another key or changed; the
     *     message names the column and never the value
     */
    public @Nullable String open(@Nullable String stored, String context) {
        if (stored == null) {
            return null;
        }
        if (!isSealed(stored)) {
            throw new IllegalStateException(plainRefused(context, 1));
        }
        return cipher().decrypt(stored, context);
    }

    /** The message that refuses plain values in a column: what is wrong and how to fix it, never a value. */
    static String plainRefused(String column, int count) {
        return column + " holds " + count + " value(s) without the v1: prefix: secrets are stored only encrypted"
                + " (ADR-0029). Set the secret again through the application, or clear the column.";
    }

    private SecretCipher cipher() {
        if (cipher == null) {
            throw new IllegalStateException(KEY_VARIABLE + " is not set: stored secrets are not available here.");
        }
        return cipher;
    }

    /** Called after {@link SecretCipher#fromBase64} accepted the value, so it decodes. */
    private static boolean isDevelopmentKey(String configured) {
        return MessageDigest.isEqual(SecretCipher.decodeKey(configured), developmentKey());
    }

    private static byte[] developmentKey() {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(DEV_KEY_SEED.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(key, SecretCipher.KEY_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JDK", e);
        }
    }
}
