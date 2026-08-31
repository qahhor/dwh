package com.greenwhite.dwh.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Argon2idPasswordHasherTest {

    private final Argon2idPasswordHasher hasher = new Argon2idPasswordHasher();

    @Test
    @DisplayName("Argon2id корректно хэширует и верифицирует валидный пароль")
    void shouldHashAndVerifyValidPassword() {
        String raw = "Qazaq#Secure2026!";
        String hash = hasher.hashPassword(raw);

        assertThat(hash).startsWith("$argon2id$");
        assertThat(hasher.verifyPassword(raw, hash)).isTrue();
        assertThat(hasher.verifyPassword("WrongPassword", hash)).isFalse();
    }

    @Test
    @DisplayName("Пустой пароль вызывает исключение")
    void shouldRejectEmptyPassword() {
        assertThatThrownBy(() -> hasher.hashPassword(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hashPassword(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SHA-256 генерация хэша токена детерминирована")
    void shouldComputeSha256Deterministically() {
        String value = "deterministic-input";
        String hash1 = Argon2idPasswordHasher.sha256(value);
        String hash2 = Argon2idPasswordHasher.sha256(value);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }
}
