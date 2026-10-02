package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import java.time.Instant;

/**
 * An invitation of a new user was issued and must reach the user's e-mail after the transaction commits (ADR-0032, 8).
 *
 * <p>The raw token lives only in this event, in memory: the database keeps its hash.
 *
 * @param channel   the e-mail the administrator gave, not yet confirmed by its owner
 * @param login     the login the invitation names, so its owner knows how to sign in
 * @param token     the one-time token of the link
 * @param expiresAt when the link stops working
 */
public record KauthInvitationIssued(
        KauthChannelRepository.ChannelRecord channel, String login, String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "KauthInvitationIssued[channel=" + channel.channel() + ", expiresAt=" + expiresAt + "]";
    }
}
