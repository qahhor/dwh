package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;

/** A contact channel bound to the viewer (FR-AUTH-5). */
public record ChannelView(
        Long id, Long userId, String channel, String address, boolean isVerified, Instant createdAt) {}
