package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;
import java.util.List;

/** A user's security state for an administrator: flags, active sessions and recent sign-in attempts. */
public record UserSecuritySummary(
        Long userId,
        String login,
        boolean is2faEnabled,
        boolean forcePasswordChange,
        Instant passwordChangedAt,
        Instant createdAt,
        long authVersion,
        int activeSessionsCount,
        List<SessionView> activeSessions,
        List<LoginAttemptView> recentLoginAttempts) {}
