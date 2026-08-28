package com.greenwhite.dwh.instance.kauth;

import com.greenwhite.dwh.instance.kauth.service.KauthPasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KauthPasswordHasherTest {

    private final KauthPasswordHasher hasher = new KauthPasswordHasher();

    @Test
    @DisplayName("Argon2id должен успешно хешировать пароль и валидировать правильный/неправильный ввод")
    void shouldHashAndVerifyPasswordCorrectly() {
        String rawPassword = "Admin123!";
        String hash = hasher.hashPassword(rawPassword);

        System.out.println("SEED_ADMIN_HASH=" + hash);

        assertThat(hash).isNotNull().startsWith("$argon2id$");
        assertThat(hasher.verifyPassword(rawPassword, hash)).isTrue();
        assertThat(hasher.verifyPassword("WrongPassword123!", hash)).isFalse();
    }

    @Test
    @DisplayName("SHA-256 должен выдавать корректный 64-символьный hex хеш")
    void shouldGenerateSha256HashCorrectly() {
        String token = "dwh_test_token_12345";
        String hash = KauthPasswordHasher.sha256(token);

        assertThat(hash).isNotNull().hasSize(64);
        assertThat(KauthPasswordHasher.sha256(token)).isEqualTo(hash);
    }
}
