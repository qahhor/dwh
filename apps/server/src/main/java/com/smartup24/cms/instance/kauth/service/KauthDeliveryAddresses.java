package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.api.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where other modules may deliver to a user (ADR-0045): the user's own confirmed address of a channel, never one nobody
 * confirmed — a typo there would hand the content to a stranger, the rule password reset follows as well. A channel is
 * deliverable when its provider is configured; a stub provider counts only while delivery is not enforced
 * ({@code smc.delivery.enforce=false}, development), as for binding a channel.
 */
@Service
public class KauthDeliveryAddresses {

    private final KauthChannelRepository channels;
    private final KauthOtpSender sender;
    private final boolean enforced;

    public KauthDeliveryAddresses(
            KauthChannelRepository channels,
            KauthOtpSender sender,
            @Value("${smc.delivery.enforce:true}") boolean enforced) {
        this.channels = channels;
        this.sender = sender;
        this.enforced = enforced;
    }

    /** Whether messages of the channel ({@code email}, {@code telegram}) reach anyone in this installation. */
    public boolean deliverable(String channel) {
        if (!KauthPref.CHANNEL_EMAIL.equals(channel) && !KauthPref.CHANNEL_TELEGRAM.equals(channel)) {
            return false;
        }
        return !enforced || !KauthOtpSender.isStub(sender.providerCode(channel));
    }

    /** The user's confirmed address of the channel: an email address or a Telegram chat; empty when there is none. */
    @Transactional(readOnly = true)
    public Optional<String> confirmedAddress(long userId, String channel) {
        return channels.findByUserIdAndChannel(userId, channel)
                .filter(KauthChannelRepository.ChannelRecord::isVerified)
                .map(KauthChannelRepository.ChannelRecord::address);
    }
}
