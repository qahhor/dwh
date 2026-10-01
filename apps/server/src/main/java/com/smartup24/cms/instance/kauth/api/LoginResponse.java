package com.smartup24.cms.instance.kauth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartup24.cms.instance.common.web.ApiDeprecations;
import com.smartup24.cms.instance.md.service.MdUserView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The answer of a sign-in step (plan 10/10, item 3.2): {@code step} is {@code otp} with the {@code otpToken} of the
 * second step, or {@code success} with the signed-in {@code user}.
 *
 * <p>The snake_case {@code otp_token} is the name this answer had before it was typed; it is sent next to
 * {@code otpToken} for one release and stops with the other deprecated forms ({@link ApiDeprecations#SUNSET},
 * ADR-0023).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        @Schema(allowableValues = {STEP_OTP, STEP_SUCCESS}) String step,
        @Nullable String otpToken,
        @Nullable MdUserView user) {

    public static final String STEP_OTP = "otp";
    public static final String STEP_SUCCESS = "success";

    /** The first step passed: the client asks for the code and sends it with this token. */
    public static LoginResponse otp(String otpToken) {
        return new LoginResponse(STEP_OTP, otpToken, null);
    }

    /** The user is signed in; the session cookie comes with the answer. */
    public static LoginResponse success(MdUserView user) {
        return new LoginResponse(STEP_SUCCESS, null, user);
    }

    /** The deprecated snake_case twin of {@link #otpToken()}, sent until the sunset of the deprecated forms. */
    @JsonProperty("otp_token")
    @Schema(deprecated = true, description = "Deprecated name of otpToken; sent until the sunset of ADR-0023 forms")
    public @Nullable String legacyOtpToken() {
        return otpToken;
    }
}
