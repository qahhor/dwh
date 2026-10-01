package com.smartup24.cms.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of secrets kept in the database (ADR-0029): AES-256-GCM from the JDK, a random 96-bit nonce per value
 * and a context (the table and column) as associated data, so a value copied into another column does not open there.
 *
 * <p>The stored form is {@code v1:} followed by base64 of {@code nonce | ciphertext | tag}. The version prefix names
 * the format and the key generation, which leaves room for a key rotation without guessing what a value is.
 */
public final class SecretCipher {

    /** The prefix of every value this cipher writes. */
    public static final String PREFIX = "v1:";

    /** AES-256: the key is exactly 32 bytes. */
    public static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /** A cipher over a raw 32-byte key; the array is copied. */
    public SecretCipher(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("the secrets key must be " + KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    /**
     * A cipher over a base64 key (standard or URL alphabet). The message of a failure never repeats the value.
     *
     * @throws IllegalArgumentException when the value is not base64 of exactly 32 bytes
     */
    public static SecretCipher fromBase64(String encoded) {
        return new SecretCipher(decodeKey(encoded));
    }

    /**
     * The raw bytes of a base64 key (standard or URL alphabet), not yet checked for length.
     *
     * @throws IllegalArgumentException when the value is empty or not base64; the message never repeats the value
     */
    public static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("the secrets key is empty");
        }
        try {
            String trimmed = encoded.trim();
            return trimmed.indexOf('-') >= 0 || trimmed.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(trimmed)
                    : Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the secrets key is not base64", e);
        }
    }

    /** Whether a stored value is in the encrypted form (and not a legacy plain value). */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Encrypts a value for one column.
     *
     * @param plain the secret, not null
     * @param context the place the value is stored, e.g. {@code kwh_subscriptions.secret_token}
     */
    public String encrypt(String plain, String context) {
        if (plain == null) {
            throw new IllegalArgumentException("nothing to encrypt");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available in this JDK", e);
        }
    }

    /**
     * Decrypts a value written by {@link #encrypt} for the same context.
     *
     * @throws IllegalStateException when the value is not in the encrypted form, was changed, was written for
     *     another context or with another key
     */
    public String decrypt(String stored, String context) {
        if (!isEncrypted(stored)) {
            throw new IllegalStateException("the value is not encrypted");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the encrypted value is not base64", e);
        }
        if (raw.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("the encrypted value is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "the encrypted value does not open: another key, another column or a changed value", e);
        }
    }
}
