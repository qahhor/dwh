package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.common.crypto.SecretCipher;
import com.smartup24.cms.instance.support.TestStoredSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0029: where the key of stored secrets comes from and when the start is refused. */
class StoredSecretsTest {

    private static final String COLUMN = "kwh_subscriptions.secret_token";

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    @DisplayName("4.6: a configured key encrypts; null stays null and a plain value is refused naming its column")
    void configuredKey() {
        StoredSecrets secrets = StoredSecrets.fromConfiguration(randomKey(), false, false);

        String sealed = secrets.seal("plain", COLUMN);
        assertThat(StoredSecrets.isSealed(sealed)).isTrue();
        assertThat(secrets.open(sealed, COLUMN)).isEqualTo("plain");
        assertThat(secrets.seal(null, COLUMN)).isNull();
        assertThat(secrets.open(null, COLUMN)).isNull();
        assertThatThrownBy(() -> secrets.open("plain-value", COLUMN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(COLUMN)
                .hasMessageContaining("v1:")
                .hasMessageNotContaining("plain-value");
    }

    @Test
    @DisplayName("4.6: outside dev the start is refused without a key, with a broken key and with the dev key")
    void refusedOutsideDev() {
        assertThatThrownBy(() -> StoredSecrets.fromConfiguration("", false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_SECRETS_KEY is not set");
        assertThatThrownBy(() -> StoredSecrets.fromConfiguration(null, false, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StoredSecrets.fromConfiguration("short", false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("SMC_SECRETS_KEY: ")
                .hasMessageNotContaining("short");

        // The dev profile encrypts with its fixed key; a value it wrote proves which key that is.
        StoredSecrets dev = StoredSecrets.fromConfiguration("", true, false);
        String devSealed = dev.seal("x", COLUMN);
        String devKey = findDevKey(devSealed);
        assertThat(StoredSecrets.fromConfiguration(devKey, true, false).open(devSealed, COLUMN))
                .isEqualTo("x");
        assertThatThrownBy(() -> StoredSecrets.fromConfiguration(devKey, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development key");
    }

    @Test
    @DisplayName("4.6: the migrate profile starts without a key and never touches a secret")
    void migrateProfileHasNoCipher() {
        StoredSecrets migrate = StoredSecrets.fromConfiguration(" ", false, true);

        assertThat(migrate.open(null, COLUMN)).isNull();
        assertThatThrownBy(() -> migrate.seal("x", COLUMN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_SECRETS_KEY");
    }

    @Test
    @DisplayName("4.6: a value sealed with one key does not open with another")
    void anotherKeyDoesNotOpen() {
        String sealed = TestStoredSecrets.secrets().seal("x", COLUMN);

        assertThatThrownBy(() -> StoredSecrets.fromConfiguration(randomKey(), false, false)
                        .open(sealed, COLUMN))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The development key is SHA-256 of its seed; recomputed here so the test does not repeat a literal key. */
    private static String findDevKey(String devSealed) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest("smartupcms-development-secrets-key".getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(key);
            assertThat(new SecretCipher(key).decrypt(devSealed, COLUMN)).isEqualTo("x");
            return encoded;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
