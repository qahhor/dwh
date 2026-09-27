package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import java.time.Instant;

/**
 * A password reset link was issued and must reach the user after the transaction commits.
 *
 * <p>The raw token lives only in this event, in memory: the database keeps its hash.
 */
public record KauthPasswordResetLinkIssued(
        KauthChannelRepository.ChannelRecord channel, String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "KauthPasswordResetLinkIssued[channel=" + channel.channel() + ", expiresAt=" + expiresAt + "]";
    }
}
