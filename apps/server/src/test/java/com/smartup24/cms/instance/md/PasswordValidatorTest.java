package com.smartup24.cms.instance.md;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-USR-2, decision of 2026-09-27: a new password has 8 to 20 characters. */
class PasswordValidatorTest {

    private final PasswordValidator validator = new PasswordValidator();

    @Test
    @DisplayName("8 and 20 characters pass; 7 and 21 are refused with the range in the message")
    void lengthIsEightToTwenty() {
        assertThatCode(() -> validator.validate("Kx7#mQ2v", "someone")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("Kx7#mQ2vLp9$wR4tZn8&", "someone")).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate("Kx7#mQ2", "someone"))
                .isInstanceOf(ApiException.class).hasMessageContaining("от 8 до 20");
        assertThatThrownBy(() -> validator.validate("Kx7#mQ2vLp9$wR4tZn8&1", "someone"))
                .isInstanceOf(ApiException.class).hasMessageContaining("от 8 до 20");
        assertThatThrownBy(() -> validator.validate(null, "someone")).isInstanceOf(ApiException.class);
    }
}
