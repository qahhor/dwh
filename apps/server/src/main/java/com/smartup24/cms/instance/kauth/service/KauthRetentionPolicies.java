package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The journals of sign-in and how long they live (plan 10/10, item 3.13). */
@Configuration(proxyBeanMethods = false)
public class KauthRetentionPolicies {

    /** Failed and successful attempts count for the lockout window only; a month keeps them for questions. */
    @Bean
    RetentionPolicy loginAttemptsRetention() {
        return new RetentionPolicy("login-attempts", "kauth_login_attempts", "attempt_at < :cutoff", 30);
    }

    /** A one-time code is dead once it expires; a week after that it is only clutter. */
    @Bean
    RetentionPolicy otpCodesRetention() {
        return new RetentionPolicy("otp-codes", "kauth_otp_codes", "expires_at < :cutoff", 7);
    }

    @Bean
    RetentionPolicy passwordResetCodesRetention() {
        return new RetentionPolicy("password-reset-codes", "kauth_password_reset_codes", "expires_at < :cutoff", 7);
    }

    /** Closed sessions: the user's list shows live ones; closed ones stay three months for a security review. */
    @Bean
    RetentionPolicy closedSessionsRetention() {
        return new RetentionPolicy(
                "closed-sessions", "kauth_sessions", "closed_at is not null and closed_at < :cutoff", 90);
    }
}
