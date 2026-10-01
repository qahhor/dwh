package com.smartup24.cms.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0029: secrets in the database are AES-256-GCM values with a version prefix. */
class SecretCipherTest {

    private static final String CONTEXT = "kwh_subscriptions.secret_token";

    private static byte[] randomKey() {
        byte[] key = new byte[SecretCipher.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private final SecretCipher cipher = new SecretCipher(randomKey());

    @Test
    @DisplayName("4.6: a value encrypts and decrypts back; the stored form carries the version prefix")
    void roundTrip() {
        String stored = cipher.encrypt("signing-key-Ж", CONTEXT);

        assertThat(stored).startsWith(SecretCipher.PREFIX).doesNotContain("signing-key");
        assertThat(SecretCipher.isEncrypted(stored)).isTrue();
        assertThat(cipher.decrypt(stored, CONTEXT)).isEqualTo("signing-key-Ж");
    }

    @Test
    @DisplayName("4.6: the same value encrypts differently each time (a fresh nonce)")
    void freshNonce() {
        assertThat(cipher.encrypt("same", CONTEXT)).isNotEqualTo(cipher.encrypt("same", CONTEXT));
    }

    @Test
    @DisplayName("4.6: a changed byte is detected")
    void tamperIsDetected() {
        String stored = cipher.encrypt("signing-key", CONTEXT);
        byte[] raw = Base64.getDecoder().decode(stored.substring(SecretCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = SecretCipher.PREFIX + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, CONTEXT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("4.6: another key does not open the value")
    void wrongKey() {
        String stored = cipher.encrypt("signing-key", CONTEXT);

        assertThatThrownBy(() -> new SecretCipher(randomKey()).decrypt(stored, CONTEXT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("4.6: a value moved to another column does not open there")
    void wrongContext() {
        String stored = cipher.encrypt("signing-key", CONTEXT);

        assertThatThrownBy(() -> cipher.decrypt(stored, "md_sso_providers.client_secret"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("4.6: a plain or broken value is not taken for an encrypted one")
    void plainAndBrokenValues() {
        assertThat(SecretCipher.isEncrypted("plain")).isFalse();
        assertThat(SecretCipher.isEncrypted(null)).isFalse();
        assertThatThrownBy(() -> cipher.decrypt("plain", CONTEXT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:%%%", CONTEXT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:AAAA", CONTEXT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("4.6: the key is base64 of exactly 32 bytes; the error never repeats the value")
    void keyFormat() {
        byte[] key = randomKey();
        String standard = Base64.getEncoder().encodeToString(key);
        String url = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        String stored = SecretCipher.fromBase64(standard).encrypt("x", CONTEXT);

        assertThat(SecretCipher.fromBase64(url).decrypt(stored, CONTEXT)).isEqualTo("x");
        assertThatThrownBy(() -> SecretCipher.fromBase64("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCipher.fromBase64("not base64 at all!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not base64 at all!");
        assertThatThrownBy(() -> SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher.encrypt(null, CONTEXT)).isInstanceOf(IllegalArgumentException.class);
    }
}
