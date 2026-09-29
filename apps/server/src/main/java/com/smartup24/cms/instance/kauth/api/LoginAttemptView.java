package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;

/** A recent sign-in attempt for a login. */
public record LoginAttemptView(
        Long id, String login, String ip, boolean isSuccess, String failureReason, Instant attemptAt) {}
