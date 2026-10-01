package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.kauth.api.LoginResponse;
import com.smartup24.cms.instance.md.service.MdUserView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plan 10/10, item 3.2: the sign-in answer is a typed record with camelCase keys only (ADR-0023). */
class LoginResponseTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("3.2: the OTP step names otpToken only, without a user")
    void otpStep() {
        JsonNode body = json.valueToTree(LoginResponse.otp("challenge"));

        assertThat(body.path("step").asString()).isEqualTo("otp");
        assertThat(body.path("otpToken").asString()).isEqualTo("challenge");
        assertThat(body.has("otp_token")).isFalse();
        assertThat(body.has("user")).isFalse();
    }

    @Test
    @DisplayName("3.2: the success step names the user and no token")
    void successStep() {
        MdUserView user = new MdUserView(
                7L, "Name", "login", null, null, "A", null, null, null, null, null, false, false, null, null, null, 1L);

        JsonNode body = json.valueToTree(LoginResponse.success(user));

        assertThat(body.path("step").asString()).isEqualTo("success");
        assertThat(body.path("user").path("id").asLong()).isEqualTo(7L);
        assertThat(body.has("otpToken")).isFalse();
        assertThat(body.has("otp_token")).isFalse();
    }
}
